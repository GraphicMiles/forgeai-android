import { useCallback, useEffect, useRef, useState } from 'react';
import Layout from './components/Layout';
import { SCREENS } from './constants/screens.js';
import ChatScreen from './components/luna/ChatScreen.jsx';
import FilesScreen from './components/luna/FilesScreen.jsx';
import ModelsScreen from './components/luna/ModelsScreen.jsx';
import SettingsScreen from './components/luna/SettingsScreen.jsx';
import useConversations from './hooks/useConversations.js';
import useWorkspace from './hooks/useWorkspace.js';
import useInference from './hooks/useInference.js';
import useAgentPipeline from './hooks/useAgentPipeline.js';
import useDeviceCapability from './hooks/useDeviceCapability';
import { haptics, isNative } from './nativeBridge';
import { recordError } from './utils/errorLog.js';
import './styles/luna.css';

export default function App() {
  const [currentScreen, setCurrentScreen] = useState(SCREENS.CHAT);
  const [endpoint, setEndpoint] = useState(
    () => localStorage.getItem('luna_endpoint') || import.meta.env.VITE_OLLAMA_URL || 'http://localhost:11434',
  );
  const [elapsed, setElapsed] = useState('');
  const noticeRef = useRef(null);

  const chat = useConversations();
  const workspace = useWorkspace({ onNotice: (text, level) => noticeRef.current?.(text, level) });
  const inference = useInference({
    endpoint,
    onFailover: ({ from, to, code }) => {
      const reason = code === 'quota_exceeded' ? 'ran out of quota'
        : code === 'rate_limited' ? 'was rate-limited'
        : code === 'server_error' ? 'had a server error' : 'was unavailable';
      noticeRef.current?.(`${from?.label || 'Provider'} ${reason} — switched to ${to?.label || 'the next provider'} to continue.`, 'info');
    },
  });
  const { deviceCapability, refresh: refreshDevice } = useDeviceCapability();

  const agent = useAgentPipeline({
    activeModel: inference.activeModel,
    provider: inference.provider,
    downloads: inference.downloads,
    runtimeInfo: inference.runtimeInfo,
    setRuntimeInfo: inference.setRuntimeInfo,
    setLastBenchmark: inference.setLastBenchmark,
    setModelStatus: inference.setModelStatus,
    messages: chat.messages,
    setMessages: chat.setMessages,
    workspaceProvider: workspace.workspaceProvider,
    reloadWorkspace: workspace.loadWorkspace,
  });
  noticeRef.current = agent.addSystemMessage;

  // A run clock, so "Working" is answerable rather than decorative.
  useEffect(() => {
    if (!agent.isTyping) { setElapsed(''); return undefined; }
    const started = Date.now();
    const tick = () => {
      const seconds = Math.floor((Date.now() - started) / 1000);
      setElapsed(seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [agent.isTyping]);

  // Clear transient "select a model" banners once a model is active.
  useEffect(() => {
    if (!inference.activeModel) return;
    chat.setMessages(prev => (prev.some(m => m.ephemeral) ? prev.filter(m => !m.ephemeral) : prev));
  }, [inference.activeModel]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSelectModel = useCallback(model => {
    inference.setActiveModel(model);
    inference.setModelStatus('idle');
    chat.setMessages(prev => prev.filter(m => !m.ephemeral));
    if (model?.source === 'cloud' || model?.cloud) { setCurrentScreen(SCREENS.CHAT); return; }
    if (model.task === 'smoke-test' || /135m/i.test(`${model.name} ${model.file}`)) {
      agent.addSystemMessage('SmolLM 135M is a runtime smoke-test model. It proves offline inference works but produces poor code.', 'warn');
    }
    if (isNative) haptics.medium();
    setCurrentScreen(SCREENS.CHAT);
  }, [agent, chat, inference]);

  const handleDeleteModel = useCallback(async model => {
    if (!window.confirm(`Delete ${model.name} permanently?`)) return;
    const result = await inference.deleteModel(model.id);
    if (result?.success) agent.addSystemMessage(result.warning || `${model.name} deleted.`, result.warning ? 'warn' : 'info');
    else agent.addSystemMessage(`Could not delete ${model.name}: ${result?.error || 'unknown error'}`, 'error');
  }, [agent, inference]);

  const handleResetApp = useCallback(() => {
    if (!window.confirm('Reset conversations and settings? Downloaded models and workspace backups are kept.')) return;
    localStorage.clear();
    chat.resetAll();
    setEndpoint('http://localhost:11434');
  }, [chat]);

  const askFromFiles = useCallback(text => {
    setCurrentScreen(SCREENS.CHAT);
    agent.handleSendMessage(text);
  }, [agent]);

  const workspaceName = workspace.workspaceRootPath
    ? (isNative ? decodeURIComponent(workspace.workspaceRootPath).split('/').pop() : 'Sandbox')
    : '';

  return (
    <Layout currentScreen={currentScreen} onScreenChange={setCurrentScreen}>
      {currentScreen === SCREENS.CHAT && (
        <ChatScreen
          messages={chat.messages}
          isTyping={agent.isTyping}
          reasoningSteps={agent.reasoningSteps}
          isAgentThinking={agent.isAgentThinking}
          pendingActions={agent.pendingActions}
          onSend={agent.handleSendMessage}
          onStop={agent.handleStopGeneration}
          onApprove={agent.handleApproveAction}
          onDiscard={agent.handleDiscardAction}
          activeModel={inference.activeModel}
          models={inference.selectableModels}
          onSelectModel={handleSelectModel}
          onOpenModels={() => setCurrentScreen(SCREENS.MODELS)}
          workspaceName={workspaceName}
          conversations={chat.conversations}
          activeConversationId={chat.activeConversationId}
          onSwitchConversation={chat.switchConversation}
          onNewConversation={chat.newConversation}
          onRenameConversation={chat.renameConversation}
          onDeleteConversation={chat.deleteConversation}
          onExportChat={chat.exportChat}
          onClearChat={chat.clearChat}
          elapsed={elapsed}
        />
      )}

      {currentScreen === SCREENS.FILES && (
        <FilesScreen
          tree={workspace.workspaceTree}
          rootPath={workspace.workspaceRootPath}
          loading={workspace.workspaceLoading}
          lastBackup={workspace.lastBackup}
          onChooseWorkspace={workspace.chooseWorkspace}
          onRefresh={() => workspace.loadWorkspace()}
          onRead={workspace.readFile}
          onSave={workspace.saveFile}
          onCreateFile={workspace.createFile}
          onCreateFolder={workspace.createFolder}
          onRename={workspace.renameFile}
          onDelete={workspace.deleteFile}
          onUndo={workspace.undo}
          onAsk={askFromFiles}
          isNative={isNative}
        />
      )}

      {currentScreen === SCREENS.MODELS && (
        <ModelsScreen
          models={inference.models}
          activeModel={inference.activeModel}
          downloads={inference.downloads}
          deviceCapability={deviceCapability}
          runtimeInfo={inference.runtimeInfo}
          benchmark={inference.lastBenchmark}
          ollamaConnected={inference.ollamaConnected}
          endpoint={endpoint}
          cloudProviders={inference.cloudProviders}
          isNative={isNative}
          onUse={handleSelectModel}
          onDelete={handleDeleteModel}
          onStop={inference.stopModel}
          onDownload={async model => {
            const result = await inference.downloadModel(model);
            if (result?.success && isNative) await haptics.success();
            else if (result?.error) recordError(new Error(result.error), 'model-download');
          }}
          onPauseDownload={model => inference.pauseDownload(model)}
          onCancelDownload={model => inference.cancelDownload(model.id)}
          onImportModel={async () => {
            try { await inference.importModel(); }
            catch (error) { recordError(error, 'model-import'); }
          }}
          onRefreshDevice={refreshDevice}
          onAddCloudProvider={config => {
            const saved = inference.addCloudProvider(config);
            agent.addSystemMessage(`${saved.label} added. Pick it from the model selector in Chat.`);
            return saved;
          }}
          onRemoveCloudProvider={id => inference.dropCloudProvider(id)}
          onUseCloudProvider={provider => { inference.selectCloudProvider(provider); setCurrentScreen(SCREENS.CHAT); }}
        />
      )}

      {currentScreen === SCREENS.SETTINGS && (
        <SettingsScreen
          endpoint={endpoint}
          onEndpointChange={setEndpoint}
          onReset={handleResetApp}
          isNative={isNative}
          runtimeInfo={inference.runtimeInfo}
          deviceCapability={deviceCapability}
        />
      )}
    </Layout>
  );
}
