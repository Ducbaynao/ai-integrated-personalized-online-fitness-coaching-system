package com.fitnesscoaching.platform.modules.chat;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocket
public class ChatSocketConfig implements WebSocketConfigurer {
    private final ChatUpdatesSocket socket;
    private final JwtDecoder decoder;
    private final UserAccountStatusQuery accounts;
    public ChatSocketConfig(ChatUpdatesSocket socket, JwtDecoder decoder, UserAccountStatusQuery accounts) {
        this.socket = socket; this.decoder = decoder; this.accounts = accounts;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/api/v1/chat/updates").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                String header = request.getHeaders().getFirst("Authorization");
                if (header == null || !header.startsWith("Bearer ")) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
                }
                try {
                    UUID actor = UUID.fromString(decoder.decode(header.substring(7)).getSubject());
                    if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE) {
                        response.setStatusCode(HttpStatus.FORBIDDEN); return false;
                    }
                    attributes.put("actor", actor);
                    return true;
                } catch (RuntimeException invalid) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
                }
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Exception exception) {}
        });
    }
}
