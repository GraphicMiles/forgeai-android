/**
 * useAgentPipeline — Luna's single execution path.
 *
 * A message goes to the model. The model decides what to do with its tools.
 * Mutating tool calls pause for approval unless execution mode is 'auto'.
 * That is the whole pipeline: no intent classification, no pre-injected
 * memory layers, no parallel patch pipeline.
 */

import { useCallback, useRef, useState } from 'react';
import { haptics, isNative } from '../nativeBridge.js';
import { getModelProfile } from '../models/catalog.js';
import { runAgenticLoop } from '../agent/agenticLoop.js';
import { createToolPolicy } from '../agent/toolPolicy.js';
import { recordError } from '../utils/errorLog.js';

const generateId = () => Math.random().toString(36).substring(2, 15);

// Trim conversation history to a rough token budget (~4 chars per token).
function trimHistory(messages, maxTokens) {
  const turns = messages.filter(m => m.role === 'user' || m.role === 'assistant');
  if (turns.length <= 2) return turns.map(({ role, content }) => ({ role, content }));
  let total = 0;
  const kept = [];
  for (let i = turns.length - 1; i >= 0; i--) {
    const tokens = Math.ceil((turns[i].content?.length || 0) / 4);
    if (total + tokens > maxTokens && kept.length >= 2) break;
    total += tokens;
    kept.unshift(turns[i]);
  }
  return kept.map(({ role, content }) => ({ role, content }));
}

function flattenTree(items = [], prefix = '') {
  const out = [];
  for (const item of items) {
    const path = prefix ? `${prefix}/${item.name}` : item.name;
    if (item.type === 'file') out.push(path);
    else if (item.children) out.push(...flattenTree(item.children, path));
  }
  return out;
}

export default function useAgentPipeline({
  activeModel,
  provider,
  downloads,
  runtimeInfo,
  setRuntimeInfo,
  setLastBenchmark,
  setModelStatus,
  messages,
  setMessages,
  workspaceProvider,
  reloadWorkspace,
}) {
  const [isTyping, setIsTyping] = useState(false);
  const [abortController, setAbortController] = useState(null);
  const [reasoningSteps, setReasoningSteps] = useState([]);
  const [isAgentThinking, setIsAgentThinking] = useState(false);
  const [pendingActions, setPendingActions] = useState([]);
  const approvalResolvers = useRef(new Map());

  const addReasoningStep = useCallback(step => setReasoningSteps(prev => [...prev, step]), []);

  const addMessage = useCallback((role, content, metadata = {}) => {
    const message = { id: generateId(), role, content, timestamp: Date.now(), ...metadata };
    setMessages(prev => [...prev, message]);
    return message;
  }, [setMessages]);

  const addSystemMessage = useCallback(
    (content, level = 'info', extra = {}) => addMessage('system', content, { level, ...extra }),
    [addMessage],
  );

  // Ask the user to approve one concrete tool call. Resolves to a boolean.
  const requestApproval = useCallback(({ tool, args, description }) => new Promise(resolve => {
    const id = generateId();
    approvalResolvers.current.set(id, resolve);
    setPendingActions(prev => [...prev, {
      id,
      type: tool,
      path: args?.path || '',
      content: typeof args?.content === 'string' ? args.content : (args?.command || ''),
      description,
    }]);
  }), []);

  const settleApproval = useCallback((actionId, approved) => {
    const resolve = approvalResolvers.current.get(actionId);
    approvalResolvers.current.delete(actionId);
    setPendingActions(prev => prev.filter(action => action.id !== actionId));
    resolve?.(approved);
  }, []);

  const handleApproveAction = useCallback(async actionId => {
    if (isNative) await haptics.medium();
    settleApproval(actionId, true);
  }, [settleApproval]);

  const handleDiscardAction = useCallback(actionId => {
    settleApproval(actionId, false);
  }, [settleApproval]);

  const handleStopGeneration = useCallback(() => abortController?.abort(), [abortController]);

  const handleSendMessage = useCallback(async text => {
    const message = String(text ?? '').trim();
    if (!message) return;

    if (!activeModel) {
      addSystemMessage('Select a model first — on-device from the Model Zoo, or a cloud provider from your collection.', 'warn', { ephemeral: true });
      return;
    }
    const download = downloads?.[activeModel.id];
    if (download && (download.status === 'downloading' || download.status === 'paused')) {
      addSystemMessage('Wait for the model download to finish before chatting.', 'warn', { ephemeral: true });
      return;
    }

    const profile = getModelProfile(activeModel);
    const userMessage = { id: generateId(), role: 'user', content: message, timestamp: Date.now() };
    const assistantId = generateId();

    setReasoningSteps([]);
    setIsAgentThinking(true);
    setMessages(prev => [...prev, userMessage, { id: assistantId, role: 'assistant', content: '', timestamp: Date.now() }]);
    setIsTyping(true);
    setModelStatus('busy');
    const controller = new AbortController();
    setAbortController(controller);
    if (isNative) await haptics.light();

    let generationResult = null;
    let loadResult = null;
    try {
      const historyBudget = Math.max(256, profile.contextTokens - profile.maxOutputTokens - 128);
      const history = trimHistory([...messages, userMessage], historyBudget);

      loadResult = await provider.loadModel(activeModel);

      if (provider.supportsToolUse) {
        const listing = await workspaceProvider?.list?.('').catch(() => null);
        const workspaceFiles = flattenTree(listing?.items || []);

        const result = await runAgenticLoop({
          provider,
          model: activeModel,
          userMessage: message,
          history,
          workspaceProvider,
          isNative,
          signal: controller.signal,
          workspaceFiles,
          toolPolicy: createToolPolicy(),
          requestApproval,
          onToken: token => setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, content: token } : m)),
          onToolCall: ({ tool, args, iteration }) => addReasoningStep({
            type: 'tool_call',
            title: `${tool} (step ${iteration})`,
            content: typeof args === 'object' ? JSON.stringify(args).slice(0, 200) : String(args).slice(0, 200),
          }),
          onIteration: ({ iteration, maxIterations, toolCalls }) => {
            if (iteration > 1) addReasoningStep({
              type: 'thought',
              title: `Step ${iteration}/${maxIterations}`,
              content: `${toolCalls} tool call(s) so far.`,
            });
          },
        });

        if (result.toolCalls.length > 0) {
          const fileActions = result.toolCalls
            .filter(call => ['create_file', 'create_folder', 'write_file', 'delete_file'].includes(call.tool))
            .map(call => ({
              type: call.tool,
              path: call.args?.path || '',
              content: call.args?.content || '',
              success: call.result?.success !== false,
            }));
          const activitySteps = result.toolCalls.map(call => ({
            tool: call.tool,
            args: call.args || {},
            result: call.result || {},
          }));
          setMessages(prev => prev.map(m => m.id === assistantId
            ? {
                ...m,
                fileActions: fileActions.length ? fileActions : undefined,
                activitySteps: activitySteps.length ? activitySteps : undefined,
                actionDuration: `${((Date.now() - m.timestamp) / 1000).toFixed(1)}s`,
              }
            : m));
          if (fileActions.some(action => action.success)) await reloadWorkspace?.();
        }
      } else {
        // Models without function calling get a plain streaming answer.
        generationResult = await provider.stream({
          model: activeModel,
          messages: history,
          signal: controller.signal,
          onToken: token => setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, content: m.content + token } : m)),
        });
      }

      if (isNative && generationResult && activeModel.source !== 'cloud' && !activeModel.cloud) {
        const info = await provider.getStatus().catch(() => runtimeInfo);
        setRuntimeInfo(info || runtimeInfo);
        setLastBenchmark({
          ...generationResult,
          modelId: activeModel.id,
          modelName: activeModel.name,
          loadMs: loadResult?.loadMs || info?.lastLoadMs || 0,
          loadReused: loadResult?.reused === true,
          abi: info?.abi || 'unknown',
          backend: info?.backend || 'llama.cpp-cpu',
          measuredAt: Date.now(),
        });
        await haptics.success();
      }
      addReasoningStep({ type: 'result_success', title: 'Done' });
    } catch (error) {
      if (error.name !== 'AbortError') {
        recordError(error, 'model-generation');
        const friendly = error.message?.includes('loaded safely')
          ? 'That model could not be loaded. It may still be downloading, or the file may be corrupted — try re-downloading it.'
          : `Something went wrong: ${error.message}`;
        setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, role: 'system', content: friendly, level: 'error' } : m));
      }
      addReasoningStep({ type: 'result_error', title: 'Failed', content: error.message });
    } finally {
      setIsTyping(false);
      setModelStatus('idle');
      setAbortController(null);
      setIsAgentThinking(false);
      // Any approval card still open belongs to a finished/aborted run.
      for (const [, resolve] of approvalResolvers.current) resolve(false);
      approvalResolvers.current.clear();
      setPendingActions([]);
    }
  }, [
    activeModel, addReasoningStep, addSystemMessage, downloads, messages, provider, reloadWorkspace,
    requestApproval, runtimeInfo, setLastBenchmark, setMessages, setModelStatus, setRuntimeInfo,
    workspaceProvider,
  ]);

  return {
    isTyping,
    reasoningSteps,
    isAgentThinking,
    pendingActions,
    handleSendMessage,
    handleStopGeneration,
    handleApproveAction,
    handleDiscardAction,
    addSystemMessage,
    addMessage,
  };
}
