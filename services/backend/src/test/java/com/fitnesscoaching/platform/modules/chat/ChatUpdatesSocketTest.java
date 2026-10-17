package com.fitnesscoaching.platform.modules.chat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatUpdatesSocketTest {
    @Test void inboxSignalContainsNoMessageBodyAndRechecksAuthority() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatUpdatesSocket socket = new ChatUpdatesSocket(chat, new ObjectMapper());
        UUID student = UUID.randomUUID(), trainer = UUID.randomUUID();
        UUID period = UUID.randomUUID(), conversation = UUID.randomUUID();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("device-1");
        when(session.getAttributes()).thenReturn(Map.<String, Object>of("actor", student));
        when(session.isOpen()).thenReturn(true);
        when(chat.conversations(student, 0, 1)).thenReturn(new ChatService.Page<>(List.of(), 0, 1, false));
        when(chat.detail(student, conversation)).thenReturn(new ChatService.Conversation(conversation, period,
                student, trainer, java.time.Instant.now(), java.time.Instant.now(), true));
        socket.afterConnectionEstablished(session);
        socket.handleTextMessage(session, new TextMessage("{\"type\":\"SUBSCRIBE_INBOX\"}"));
        socket.changed(new ChatService.Changed(student, trainer, period, conversation));
        var sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(sent.capture());
        assertThat(sent.getAllValues().get(1).getPayload()).contains("CHAT_UPDATED", conversation.toString())
                .doesNotContain("body", "secret");
        verify(chat).detail(student, conversation);

        when(chat.detail(student, conversation)).thenThrow(new ChatFailure("CHAT_CONVERSATION_NOT_FOUND", 404));
        socket.changed(new ChatService.Changed(student, trainer, period, conversation));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test void updatesAreDeclaredPostCommitOnly() throws Exception {
        var method = ChatUpdatesSocket.class.getMethod("changed", ChatService.Changed.class);
        var listener = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
