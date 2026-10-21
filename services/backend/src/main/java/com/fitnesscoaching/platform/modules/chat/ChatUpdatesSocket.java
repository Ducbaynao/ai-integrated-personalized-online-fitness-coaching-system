package com.fitnesscoaching.platform.modules.chat;

import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import com.fitnesscoaching.platform.modules.notification.LifecycleNotificationWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Post-commit invalidation only; bodies and read receipts are fetched over authorized REST. */
@Component
public class ChatUpdatesSocket extends TextWebSocketHandler {
    private final ChatService chat;
    private final InAppNotificationService notifications;
    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> inboxSubscriptions = ConcurrentHashMap.newKeySet();

    public ChatUpdatesSocket(ChatService chat, InAppNotificationService notifications, ObjectMapper mapper) {
        this.chat = chat; this.notifications = notifications; this.mapper = mapper;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.put(session.getId(), session); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId()); subscriptions.remove(session.getId()); inboxSubscriptions.remove(session.getId());
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID actor = (UUID) session.getAttributes().get("actor");
        try {
            var json = mapper.readTree(message.getPayload());
            if ("SUBSCRIBE_INBOX".equals(json.path("type").asText())) {
                chat.conversations(actor, 0, 1);
                inboxSubscriptions.add(session.getId());
                session.sendMessage(new TextMessage("{\"type\":\"SUBSCRIBED\"}"));
                return;
            }
            if (!"SUBSCRIBE".equals(json.path("type").asText())) throw new IllegalArgumentException();
            UUID conversation = UUID.fromString(json.path("conversationId").asText());
            chat.detail(actor, conversation);
            subscriptions.put(session.getId(), conversation);
            session.sendMessage(new TextMessage("{\"type\":\"SUBSCRIBED\"}"));
        } catch (RuntimeException invalid) {
            subscriptions.remove(session.getId());
            inboxSubscriptions.remove(session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(ChatService.Changed changed) {
        for (var entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (!session.isOpen() || (!changed.conversationId().equals(subscriptions.get(entry.getKey()))
                    && !inboxSubscriptions.contains(entry.getKey()))) continue;
            UUID actor = (UUID) session.getAttributes().get("actor");
            try {
                chat.detail(actor, changed.conversationId());
                synchronized (session) {
                    if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"CHAT_UPDATED\",\"conversationId\":\""
                            + changed.conversationId() + "\"}"));
                }
            } catch (ChatFailure | IOException unavailable) {
                subscriptions.remove(entry.getKey());
                inboxSubscriptions.remove(entry.getKey());
                try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void lifecycleChanged(LifecycleNotificationWriter.Changed changed) {
        for (var entry : sessions.entrySet()) {
            if (!inboxSubscriptions.contains(entry.getKey())) continue;
            WebSocketSession session = entry.getValue();
            UUID actor = (UUID) session.getAttributes().get("actor");
            if (!session.isOpen() || !changed.recipient().equals(actor)) continue;
            try {
                if (!notifications.canReadNotice(actor, changed.notificationId())) continue;
                synchronized (session) {
                    if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"INBOX_UPDATED\"}"));
                }
            } catch (RuntimeException | IOException unavailable) {
                subscriptions.remove(entry.getKey());
                inboxSubscriptions.remove(entry.getKey());
                try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { }
            }
        }
    }
}
