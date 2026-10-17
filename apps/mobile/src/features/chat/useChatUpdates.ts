import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { API_BASE_URL } from '@/config/env';
import { tokenStorage } from '@/services/storage';

/** Best-effort invalidation only. The REST read remains authoritative after reconnect. */
export function useChatUpdates(actor: string, role: string, conversationId?: string) {
  const cache = useQueryClient();
  useEffect(() => {
    if (!actor) return;
    let disposed = false;
    let socket: WebSocket | null = null;
    let reconnect: ReturnType<typeof setTimeout> | null = null;
    const connect = async () => {
      const token = await tokenStorage.getAccessToken();
      if (disposed || !token) return;
      const url = `${API_BASE_URL.replace(/^http/, 'ws')}/chat/updates`;
      // React Native supports request headers on its native WebSocket constructor.
      const NativeWebSocket = WebSocket as unknown as new (url: string, protocols: string[],
        options: { headers: Record<string, string> }) => WebSocket;
      socket = new NativeWebSocket(url, [], { headers: { Authorization: `Bearer ${token}` } });
      socket.onopen = () => {
        socket?.send(JSON.stringify(conversationId
          ? { type: 'SUBSCRIBE', conversationId } : { type: 'SUBSCRIBE_INBOX' }));
        void cache.invalidateQueries({ queryKey: ['chat', actor, role] });
      };
      socket.onmessage = event => {
        try {
          const signal = JSON.parse(String(event.data));
          if (signal.type === 'CHAT_UPDATED' && (!conversationId || signal.conversationId === conversationId))
            void cache.invalidateQueries({ queryKey: ['chat', actor, role] });
        } catch { /* An invalid hint cannot become authoritative data. */ }
      };
      socket.onclose = event => {
        if (!disposed) {
          if (event.code === 1008) {
            cache.removeQueries({ queryKey: ['chat', actor, role] });
          } else {
            void cache.invalidateQueries({ queryKey: ['chat', actor, role] });
            reconnect = setTimeout(() => void connect(), 5000);
          }
        }
      };
      socket.onerror = () => socket?.close();
    };
    void connect();
    return () => {
      disposed = true;
      if (reconnect) clearTimeout(reconnect);
      socket?.close();
    };
  }, [actor, role, conversationId, cache]);
}
