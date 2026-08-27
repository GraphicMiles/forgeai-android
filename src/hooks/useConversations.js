/**
 * useConversations — chat + conversation list state and persistence.
 * Pure UI state; no agent logic lives here.
 */

import { useCallback, useEffect, useState } from 'react';
import { recordError } from '../utils/errorLog.js';

const generateId = () => Math.random().toString(36).substring(2, 15);
const defaultTitle = () => `Chat ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key) || fallback); }
  catch { return JSON.parse(fallback); }
}

export default function useConversations() {
  const [conversations, setConversations] = useState(() => readJson('luna_conversations', '[]'));
  const [activeConversationId, setActiveConversationId] = useState(() => localStorage.getItem('luna_active_conversation') || '');
  const [messages, setMessages] = useState(() => readJson('luna_chat', '[]'));

  // Ephemeral messages (transient warnings) are never persisted.
  useEffect(() => {
    try { localStorage.setItem('luna_chat', JSON.stringify(messages.filter(m => !m.ephemeral))); }
    catch (error) { recordError(error, 'persist-chat'); }
  }, [messages]);

  useEffect(() => {
    if (activeConversationId) return;
    const id = generateId();
    setActiveConversationId(id);
    setConversations([{ id, title: defaultTitle(), messages }]);
  }, [activeConversationId, messages]);

  useEffect(() => {
    try {
      localStorage.setItem('luna_conversations', JSON.stringify(conversations));
      localStorage.setItem('luna_active_conversation', activeConversationId);
    } catch (error) {
      recordError(error, 'persist-conversations');
    }
  }, [conversations, activeConversationId]);

  useEffect(() => {
    if (!activeConversationId) return;
    setConversations(prev => prev.map(c => c.id === activeConversationId
      ? { ...c, messages: messages.filter(m => !m.ephemeral) }
      : c));
  }, [messages, activeConversationId]);

  const newConversation = useCallback(() => {
    const id = generateId();
    setConversations(prev => [...prev, { id, title: defaultTitle(), messages: [] }]);
    setActiveConversationId(id);
    setMessages([]);
  }, []);

  const switchConversation = useCallback(id => {
    const target = conversations.find(c => c.id === id);
    if (!target) return;
    setActiveConversationId(id);
    setMessages(Array.isArray(target.messages) ? target.messages : []);
  }, [conversations]);

  const renameConversation = useCallback((id = activeConversationId) => {
    const current = conversations.find(c => c.id === id);
    if (!current) return;
    const title = window.prompt('Conversation name', current.title);
    if (title?.trim()) setConversations(prev => prev.map(c => c.id === id ? { ...c, title: title.trim() } : c));
  }, [conversations, activeConversationId]);

  const deleteConversation = useCallback((id = activeConversationId) => {
    if (conversations.length <= 1 || !window.confirm('Delete this conversation?')) return;
    const next = conversations.filter(c => c.id !== id);
    if (next.length === 0) return;
    setConversations(next);
    if (id === activeConversationId) {
      setActiveConversationId(next[0].id);
      setMessages(Array.isArray(next[0].messages) ? next[0].messages : []);
    }
  }, [conversations, activeConversationId]);

  const clearChat = useCallback((id = activeConversationId) => {
    if (!window.confirm('Clear this conversation?')) return;
    setConversations(prev => prev.map(c => c.id === id ? { ...c, messages: [] } : c));
    if (id === activeConversationId) setMessages([]);
  }, [activeConversationId]);

  const exportChat = useCallback((id = activeConversationId) => {
    const source = id === activeConversationId ? messages : (conversations.find(c => c.id === id)?.messages || []);
    const blob = new Blob(
      [source.map(m => `${String(m.role || 'message').toUpperCase()}\n${String(m.content ?? '')}`).join('\n\n')],
      { type: 'text/plain' },
    );
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'luna-chat.txt';
    link.click();
    URL.revokeObjectURL(link.href);
  }, [messages, conversations, activeConversationId]);

  const resetAll = useCallback(() => {
    setMessages([]);
    setConversations([]);
    setActiveConversationId('');
  }, []);

  return {
    conversations,
    activeConversationId,
    messages,
    setMessages,
    newConversation,
    switchConversation,
    renameConversation,
    deleteConversation,
    clearChat,
    exportChat,
    resetAll,
  };
}
