/**
 * useWorkspace — workspace provider, file tree, and every file operation the UI
 * performs. Extracted from App.jsx so the component stays a composition root.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { isNative, pickWorkspaceFolder } from '../nativeBridge.js';
import { createSafWorkspaceProvider, createVirtualWorkspaceProvider } from '../workspace/workspaceProvider.js';
import { recordError } from '../utils/errorLog.js';

export default function useWorkspace({ onNotice } = {}) {
  const [workspaceTree, setWorkspaceTree] = useState([]);
  const [workspaceLoading, setWorkspaceLoading] = useState(false);
  const [workspaceRootPath, setWorkspaceRootPath] = useState(() => localStorage.getItem('luna_workspace_uri') || '');
  const [selectedFilePath, setSelectedFilePath] = useState('');
  const [lastBackup, setLastBackup] = useState(null);
  const [modelFolderUri, setModelFolderUri] = useState(() => localStorage.getItem('luna_model_folder_uri') || '');

  const notice = useCallback((text, level = 'info') => onNotice?.(text, level), [onNotice]);

  const workspaceProvider = useMemo(
    () => (isNative ? createSafWorkspaceProvider(workspaceRootPath) : createVirtualWorkspaceProvider()),
    [workspaceRootPath],
  );

  const loadWorkspace = useCallback(async (providerOverride = workspaceProvider) => {
    setWorkspaceLoading(true);
    try {
      if (!providerOverride.available) {
        setWorkspaceTree([]);
        setLastBackup(null);
        return;
      }
      setWorkspaceTree(await providerOverride.list());
      const backups = await providerOverride.listBackups().catch(() => []);
      setLastBackup(backups[0] || null);
    } catch (error) {
      console.warn('Failed to load workspace:', error);
      setWorkspaceTree([]);
      setLastBackup(null);
    } finally {
      setWorkspaceLoading(false);
    }
  }, [workspaceProvider]);

  useEffect(() => {
    if (isNative && workspaceRootPath && !workspaceRootPath.startsWith('content://')) {
      localStorage.removeItem('luna_workspace_uri');
      setWorkspaceRootPath('');
      setWorkspaceTree([]);
      return;
    }
    if (!isNative && workspaceRootPath !== 'virtual://workspace') {
      setWorkspaceRootPath('virtual://workspace');
    }
    setSelectedFilePath('');
    loadWorkspace();
  }, [loadWorkspace, workspaceRootPath]);

  const rememberBackup = useCallback((result, fallbackPath) => {
    if (result?.backupId) setLastBackup({
      id: result.backupId,
      path: result.path || fallbackPath,
      operation: result.operation || 'write',
      createdAt: Date.now(),
    });
  }, []);

  const chooseWorkspace = useCallback(async () => {
    if (!isNative) return;
    const result = await pickWorkspaceFolder();
    if (!result?.uri) return;
    try { localStorage.setItem('luna_workspace_uri', result.uri); }
    catch (error) { recordError(error, 'persist-workspace-uri'); }
    setWorkspaceRootPath(result.uri);
    setSelectedFilePath('');
    await loadWorkspace(createSafWorkspaceProvider(result.uri));
  }, [loadWorkspace]);

  const chooseModelFolder = useCallback(async () => {
    if (!isNative) return;
    const result = await pickWorkspaceFolder();
    if (!result?.uri) return;
    try { localStorage.setItem('luna_model_folder_uri', result.uri); }
    catch (error) { recordError(error, 'persist-model-folder'); }
    setModelFolderUri(result.uri);
  }, []);

  const readFile = useCallback(path => workspaceProvider.readText(path), [workspaceProvider]);

  const saveFile = useCallback(async (path, content) => {
    const result = await workspaceProvider.writeText(path, content);
    rememberBackup(result, path);
    await loadWorkspace();
  }, [loadWorkspace, rememberBackup, workspaceProvider]);

  const createFile = useCallback(async path => {
    try {
      await workspaceProvider.createFile(path);
      await loadWorkspace();
    } catch (error) {
      recordError(error, 'workspace-create-file');
      throw error;
    }
  }, [loadWorkspace, workspaceProvider]);

  const createFolder = useCallback(async path => {
    try {
      await workspaceProvider.createFolder(path);
      await loadWorkspace();
    } catch (error) {
      recordError(error, 'workspace-create-folder');
      throw error;
    }
  }, [loadWorkspace, workspaceProvider]);

  const writeFileFromChat = useCallback(async (path, content) => {
    if (!workspaceProvider?.writeText) throw new Error('No workspace selected. Please choose a folder first.');
    await workspaceProvider.writeText(path, content);
    await loadWorkspace();
  }, [loadWorkspace, workspaceProvider]);

  const renameFile = useCallback(async (oldPath, newPath) => {
    const result = await workspaceProvider.rename(oldPath, newPath.split('/').pop());
    rememberBackup(result, oldPath);
    await loadWorkspace();
  }, [loadWorkspace, rememberBackup, workspaceProvider]);

  const deleteFile = useCallback(async path => {
    const result = await workspaceProvider.delete(path);
    rememberBackup(result, path);
    await loadWorkspace();
  }, [loadWorkspace, rememberBackup, workspaceProvider]);

  const undo = useCallback(async () => {
    const backupIds = lastBackup?.ids || (lastBackup?.id ? [lastBackup.id] : []);
    if (backupIds.length === 0) return;
    try {
      const restored = [];
      for (const id of [...backupIds].reverse()) restored.push(await workspaceProvider.restoreBackup(id));
      setLastBackup(null);
      await loadWorkspace();
      notice(`Restored ${restored.map(item => item.path).join(', ')} from the last workspace transaction.`);
    } catch (error) {
      recordError(error, 'workspace-restore');
      notice(`Workspace restore failed: ${error.message}`, 'error');
    }
  }, [lastBackup, loadWorkspace, notice, workspaceProvider]);

  return {
    workspaceProvider,
    workspaceTree,
    workspaceLoading,
    workspaceRootPath,
    selectedFilePath,
    setSelectedFilePath,
    lastBackup,
    modelFolderUri,
    loadWorkspace,
    chooseWorkspace,
    chooseModelFolder,
    readFile,
    saveFile,
    createFile,
    createFolder,
    writeFileFromChat,
    renameFile,
    deleteFile,
    undo,
  };
}
