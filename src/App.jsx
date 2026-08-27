import { useCallback, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import Layout from './components/Layout';
import { SCREENS } from './constants/screens.js';
import ChatContainer from './components/ChatContainer';
import ModelZoo from './components/ModelZoo';
import MyCollection from './components/MyCollection';
import Workspace from './components/Workspace';
import Settings from './components/Settings';
import useConversations from './hooks/useConversations.js';
import useWorkspace from './hooks/useWorkspace.js';
import useInference from './hooks/useInference.js';
import useAgentPipeline from './hooks/useAgentPipeline.js';
import useDeviceCapability from './hooks/useDeviceCapability';
import { haptics, isNative } from './nativeBridge';
import { recordError } from './utils/errorLog.js';
import './styles/index.css';

const screenVariants = {
  initial: { opacity: 0, x: 20 },
  animate: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: -20 },
};

function Screen({ id, children }) {
  return (
    <motion.div key={id} variants={screenVariants} initial="initial" animate="animate" exit="exit" className="screen-container">
      {children}
    </motion.div>
  );
}

export default function App() {
  const [currentScreen, setCurrentScreen] = useState(SCREENS.CHAT);
  const [endpoint, setEndpoint] = useState(
    () => localStorage.getItem('luna_endpoint') || import.meta.env.VITE_OLLAMA_URL || 'http://localhost:11434',
  );
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

  // Clear transient "select a model" banners once a model is active.
  useEffect(() => {
    if (!inference.activeModel) return;
    chat.setMessages(prev => {
      const isStale = m => m.ephemeral;
      return prev.some(isStale) ? prev.filter(m => !isStale(m)) : prev;
    });
  }, [inference.activeModel]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleFilePick = useCallback((path, node) => {
    workspace.setSelectedFilePath(path);
    navigator.clipboard?.writeText(path).catch(() => {});
    agent.addSystemMessage(`Selected ${node?.type === 'folder' ? 'folder' : 'file'}: ${path.split('/').pop()} (path copied)`);
    setCurrentScreen(SCREENS.CHAT);
  }, [agent, workspace]);

  const handleFileCreateFromChat = useCallback(async (path, content) => {
    await workspace.writeFileFromChat(path, content);
    setCurrentScreen(SCREENS.WORKSPACE);
  }, [workspace]);

  const handleFileOpenFromChat = useCallback(path => {
    workspace.setSelectedFilePath(path);
    setCurrentScreen(SCREENS.WORKSPACE);
  }, [workspace]);

  const handleSelectModel = useCallback(model => {
    inference.setActiveModel(model);
    inference.setModelStatus('idle');
    chat.setMessages(prev => prev.filter(m => !m.ephemeral));
    if (model?.source === 'cloud' || model?.cloud) {
      setCurrentScreen(SCREENS.CHAT);
      return;
    }
    if (model.task === 'smoke-test' || /135m/i.test(`${model.name} ${model.file}`)) {
      agent.addSystemMessage('SmolLM 135M is a runtime smoke-test model. It proves offline inference works but produces poor code.', 'warn');
    }
    if (isNative) haptics.medium();
    setTimeout(() => setCurrentScreen(SCREENS.CHAT), 500);
  }, [agent, chat, inference]);

  const handleAddCloudProvider = useCallback(config => {
    const saved = inference.addCloudProvider(config);
    agent.addSystemMessage(`${saved.label} added. It is now available from the chat model selector.`);
    return saved;
  }, [agent, inference]);

  const handleSelectCloudProvider = useCallback(config => {
    inference.selectCloudProvider(config);
    setCurrentScreen(SCREENS.CHAT);
  }, [inference]);

  const handleDeleteModel = useCallback(async model => {
    if (!window.confirm(`Delete ${model.name} permanently?`)) return;
    const result = await inference.deleteModel(model.id);
    if (result?.success) agent.addSystemMessage(result.warning || `${model.name} deleted.`, result.warning ? 'warn' : 'info');
    else agent.addSystemMessage(`Could not delete ${model.name}: ${result?.error || 'unknown error'}`, 'error');
  }, [agent, inference]);

  const handleResetApp = useCallback(() => {
    if (!window.confirm('Reset conversations, model metadata, and settings? Downloaded models and workspace backups are not deleted.')) return;
    localStorage.clear();
    chat.resetAll();
    setEndpoint('http://localhost:11434');
  }, [chat]);

  return (
    <Layout
      model={inference.activeModel?.name || 'No model'}
      status={inference.modelStatus}
      ollamaConnected={inference.ollamaConnected}
      onScreenChange={setCurrentScreen}
      currentScreen={currentScreen}
      isConnecting={inference.isConnecting}
    >
      <AnimatePresence mode="wait">
        {currentScreen === SCREENS.CHAT && (
          <Screen id="chat">
            <ChatContainer
              messages={chat.messages}
              isTyping={agent.isTyping}
              reasoningSteps={agent.reasoningSteps}
              isAgentThinking={agent.isAgentThinking}
              pendingActions={agent.pendingActions}
              onSendMessage={agent.handleSendMessage}
              onStopGeneration={agent.handleStopGeneration}
              onApproveAction={agent.handleApproveAction}
              onDiscardAction={agent.handleDiscardAction}
              noModelSelected={!inference.activeModel}
              ollamaConnected={inference.ollamaConnected}
              isNative={isNative}
              conversations={chat.conversations}
              activeConversationId={chat.activeConversationId}
              onConversationChange={chat.switchConversation}
              onNewConversation={chat.newConversation}
              onRenameConversation={chat.renameConversation}
              onDeleteConversation={chat.deleteConversation}
              onExportChat={chat.exportChat}
              onClearChat={chat.clearChat}
              onOpenZoo={() => setCurrentScreen(SCREENS.ZOO)}
              onOpenCollection={() => setCurrentScreen(SCREENS.COLLECTION)}
              activeModel={inference.activeModel}
              availableModels={inference.selectableModels}
              onModelChange={handleSelectModel}
              onFileCreate={handleFileCreateFromChat}
              onFileOpen={handleFileOpenFromChat}
            />
          </Screen>
        )}

        {currentScreen === SCREENS.ZOO && (
          <Screen id="zoo">
            <ModelZoo
              downloadedModels={inference.models}
              downloads={inference.downloads}
              onDownload={async (model, onProgress) => {
                const result = await inference.downloadModel(model, onProgress);
                if (result.success && isNative) await haptics.success();
                else if (result.error) recordError(new Error(result.error), 'model-download');
              }}
              onPause={model => inference.pauseDownload(model)}
              onCancel={model => inference.cancelDownload(model.id)}
              onUseModel={handleSelectModel}
              onMountModel={async model => {
                const result = await inference.mountModel(model);
                if (!result.success) recordError(new Error(result.error), 'model-mount');
              }}
              deviceCapability={deviceCapability}
              ollamaConnected={inference.ollamaConnected}
              isNative={isNative}
              onClose={() => setCurrentScreen(SCREENS.COLLECTION)}
              onChooseModelFolder={workspace.chooseModelFolder}
              modelFolderSelected={workspace.modelFolderUri.startsWith('content://')}
            />
          </Screen>
        )}

        {currentScreen === SCREENS.COLLECTION && (
          <Screen id="collection">
            <MyCollection
              models={inference.models}
              activeModel={inference.activeModel}
              onSelect={handleSelectModel}
              onDelete={handleDeleteModel}
              onStop={inference.stopModel}
              isRunning={inference.modelStatus === 'busy'}
              ollamaConnected={inference.ollamaConnected}
              runtimeMode={isNative ? 'On-device runtime' : 'Ollama preview'}
              runtimeInfo={inference.runtimeInfo}
              benchmark={inference.lastBenchmark}
              deviceCapability={deviceCapability}
              onOpenZoo={() => setCurrentScreen(SCREENS.ZOO)}
              onImportModel={async () => {
                try { await inference.importModel(); }
                catch (error) { recordError(error, 'model-import'); }
              }}
              onRefreshDevice={refreshDevice}
              onMountModel={async model => {
                const result = await inference.mountModel(model);
                if (!result.success) recordError(new Error(result.error), 'model-mount');
              }}
              onUnmountModel={() => inference.unmountModel()}
              isNative={isNative}
              cloudProviders={inference.cloudProviders}
              onAddCloudProvider={handleAddCloudProvider}
              onRemoveCloudProvider={inference.dropCloudProvider}
              onSelectCloudModel={handleSelectCloudProvider}
            />
          </Screen>
        )}

        {currentScreen === SCREENS.WORKSPACE && (
          <Screen id="workspace">
            <Workspace
              workspace={{ name: 'Device Storage', path: workspace.workspaceRootPath, tree: workspace.workspaceTree }}
              workspaceLoading={workspace.workspaceLoading}
              onFileSelect={() => {}}
              onFilePick={handleFilePick}
              onFileRead={workspace.readFile}
              onFileSave={workspace.saveFile}
              onFileCreate={workspace.createFile}
              onFolderCreate={workspace.createFolder}
              onFileRename={workspace.renameFile}
              onFileDelete={workspace.deleteFile}
              onUndo={workspace.undo}
              undoPath={workspace.lastBackup?.path || ''}
              onRefresh={() => workspace.loadWorkspace()}
              onChooseWorkspace={workspace.chooseWorkspace}
            />
          </Screen>
        )}

        {currentScreen === SCREENS.SETTINGS && (
          <Screen id="settings">
            <Settings
              endpoint={endpoint}
              onEndpointChange={setEndpoint}
              onReset={handleResetApp}
              isNative={isNative}
            />
          </Screen>
        )}
      </AnimatePresence>
    </Layout>
  );
}
