/**
 * Virtual Workspace for Web/Desktop
 * 
 * Provides in-memory file system simulation for non-Android platforms.
 * Allows users to create, edit, and manage files in the browser.
 * Files can be exported as a ZIP or downloaded individually.
 */

const VIRTUAL_STORAGE_KEY = 'luna_virtual_workspace';

export class VirtualWorkspace {
  constructor() {
    this.files = new Map(); // path -> { content, type, createdAt }
    this.folders = new Set();
    this.backups = new Map(); // id -> { path, content, createdAt }
    this.loadFromStorage();
  }

  loadFromStorage() {
    if (typeof localStorage === 'undefined') return;
    try {
      const saved = localStorage.getItem(VIRTUAL_STORAGE_KEY);
      if (saved) {
        const data = JSON.parse(saved);
        this.files = new Map(data.files || []);
        this.folders = new Set(data.folders || []);
        this.backups = new Map(data.backups || []);
      }
    } catch (error) {
      console.warn('Failed to load virtual workspace:', error);
    }
  }

  saveToStorage() {
    if (typeof localStorage === 'undefined') return;
    try {
      const data = {
        files: Array.from(this.files.entries()),
        folders: Array.from(this.folders),
        backups: Array.from(this.backups.entries()),
      };
      localStorage.setItem(VIRTUAL_STORAGE_KEY, JSON.stringify(data));
    } catch (error) {
      console.warn('Failed to save virtual workspace:', error);
    }
  }

  // Get a tree structure compatible with Workspace component
  getTree() {
    const tree = [];

    // Create folders
    for (const folderPath of this.folders) {
      const parts = folderPath.split('/').filter(Boolean);
      let current = tree;
      let pathSoFar = '';

      for (const part of parts) {
        pathSoFar = pathSoFar ? `${pathSoFar}/${part}` : part;
        let folder = current.find(n => n.name === part && n.type === 'folder');
        
        if (!folder) {
          folder = {
            name: part,
            path: pathSoFar,
            type: 'folder',
            children: [],
          };
          current.push(folder);
        }
        current = folder.children;
      }
    }

    // Add files
    for (const path of this.files.keys()) {
      const parts = path.split('/').filter(Boolean);
      const fileName = parts.pop();
      let current = tree;
      let pathSoFar = '';

      for (const part of parts) {
        pathSoFar = pathSoFar ? `${pathSoFar}/${part}` : part;
        let folder = current.find(n => n.name === part && n.type === 'folder');
        if (!folder) {
          folder = {
            name: part,
            path: pathSoFar,
            type: 'folder',
            children: [],
          };
          current.push(folder);
        }
        current = folder.children;
      }

      current.push({
        name: fileName,
        path,
        type: 'file',
      });
    }

    // Sort: folders first, then files
    const sortTree = (nodes) => {
      nodes.sort((a, b) => {
        if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
        return a.name.localeCompare(b.name);
      });
      for (const node of nodes) {
        if (node.children) sortTree(node.children);
      }
    };

    sortTree(tree);
    return tree;
  }

  async inspect(path) {
    const file = this.files.get(path);
    if (!file) throw new Error(`File not found: ${path}`);
    const content = String(file.content ?? '');
    return {
      type: 'file',
      binary: content.includes('\0'),
      mimeType: 'text/plain',
      size: new TextEncoder().encode(content).byteLength,
    };
  }

  async readFile(path) {
    const file = this.files.get(path);
    if (!file) throw new Error(`File not found: ${path}`);
    return file.content;
  }

  addBackup(record) {
    const id = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    this.backups.set(id, { ...record, createdAt: Date.now() });
    const oldest = [...this.backups.entries()].sort((a, b) => a[1].createdAt - b[1].createdAt);
    while (oldest.length > 20) {
      const [expiredId] = oldest.shift();
      this.backups.delete(expiredId);
    }
    return id;
  }

  async writeFile(path, content = '') {
    const dir = path.substring(0, path.lastIndexOf('/'));
    if (dir) this.createDirectory(dir);
    const existing = this.files.get(path);
    const backupId = existing
      ? this.addBackup({ operation: 'write', path, content: String(existing.content ?? '') })
      : null;
    const text = String(content);
    this.files.set(path, {
      content: text,
      type: 'file',
      createdAt: existing?.createdAt || Date.now(),
      updatedAt: Date.now(),
    });
    this.saveToStorage();
    return { path, size: new TextEncoder().encode(text).byteLength, backupId, operation: 'write' };
  }

  async listBackups() {
    return [...this.backups.entries()]
      .map(([id, backup]) => ({ id, path: backup.path, operation: backup.operation, createdAt: backup.createdAt }))
      .sort((a, b) => b.createdAt - a.createdAt);
  }

  async restoreBackup(id) {
    const backup = this.backups.get(id);
    if (!backup) throw new Error('Workspace backup is missing or expired.');
    if (backup.operation === 'write') {
      this.files.set(backup.path, { content: backup.content, type: 'file', createdAt: Date.now(), restoredAt: Date.now() });
    } else if (backup.operation === 'delete') {
      for (const [path] of backup.files || []) if (this.files.has(path)) throw new Error(`Cannot restore because ${path} already exists.`);
      for (const path of backup.folders || []) if (this.folders.has(path)) throw new Error(`Cannot restore because ${path} already exists.`);
      for (const [path, file] of backup.files || []) this.files.set(path, file);
      for (const path of backup.folders || []) this.folders.add(path);
    } else if (backup.operation === 'rename') {
      await this.renameInternal(backup.newPath, backup.path, false);
    } else {
      throw new Error('Unsupported workspace backup operation.');
    }
    this.backups.delete(id);
    this.saveToStorage();
    return { path: backup.path, operation: backup.operation, restored: true };
  }

  async createDirectory(path) {
    if (!path) return;
    this.folders.add(path);
    const parent = path.substring(0, path.lastIndexOf('/'));
    if (parent) this.createDirectory(parent);
    this.saveToStorage();
  }

  async deleteFile(path) {
    const isFile = this.files.has(path);
    const isFolder = this.folders.has(path);
    if (!isFile && !isFolder) throw new Error(`Workspace item not found: ${path}`);
    const files = [...this.files.entries()].filter(([filePath]) => filePath === path || filePath.startsWith(`${path}/`));
    const folders = [...this.folders].filter(folderPath => folderPath === path || folderPath.startsWith(`${path}/`));
    const backupId = this.addBackup({ operation: 'delete', path, files, folders });
    for (const [filePath] of files) this.files.delete(filePath);
    for (const folderPath of folders) this.folders.delete(folderPath);
    this.saveToStorage();
    return { path, backupId, operation: 'delete' };
  }

  async renameInternal(oldPath, newPath, createBackup) {
    if (this.files.has(newPath) || this.folders.has(newPath)) throw new Error(`Workspace item already exists: ${newPath}`);
    const isFile = this.files.has(oldPath);
    const isFolder = this.folders.has(oldPath);
    if (!isFile && !isFolder) throw new Error(`Workspace item not found: ${oldPath}`);
    const backupId = createBackup ? this.addBackup({ operation: 'rename', path: oldPath, newPath }) : null;
    if (isFile) {
      const file = this.files.get(oldPath);
      this.files.delete(oldPath);
      this.files.set(newPath, file);
    } else {
      const folderEntries = [...this.folders].filter(path => path === oldPath || path.startsWith(`${oldPath}/`));
      const fileEntries = [...this.files.entries()].filter(([path]) => path.startsWith(`${oldPath}/`));
      for (const path of folderEntries) this.folders.delete(path);
      for (const [path] of fileEntries) this.files.delete(path);
      for (const path of folderEntries) this.folders.add(newPath + path.slice(oldPath.length));
      for (const [path, file] of fileEntries) this.files.set(newPath + path.slice(oldPath.length), file);
    }
    this.saveToStorage();
    return { oldPath, newPath, backupId, operation: 'rename' };
  }

  async rename(oldPath, newPath) {
    return this.renameInternal(oldPath, newPath, true);
  }

  // Export all files as a downloadable structure
  exportAsDownload() {
    const data = {
      files: Array.from(this.files.entries()),
      folders: Array.from(this.folders),
      exportedAt: new Date().toISOString(),
    };
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `luna-workspace-${Date.now()}.json`;
    a.click();
    
    URL.revokeObjectURL(url);
  }

  // Import from exported JSON
  importFromJSON(jsonString) {
    try {
      const data = JSON.parse(jsonString);
      this.files = new Map(data.files || []);
      this.folders = new Set(data.folders || []);
      this.backups = new Map();
      this.saveToStorage();
      return true;
    } catch (error) {
      console.error('Import failed:', error);
      return false;
    }
  }

  clear() {
    this.files.clear();
    this.folders.clear();
    this.backups.clear();
    if (typeof localStorage !== 'undefined') localStorage.removeItem(VIRTUAL_STORAGE_KEY);
  }
}

// Singleton instance
export const virtualWorkspace = new VirtualWorkspace();