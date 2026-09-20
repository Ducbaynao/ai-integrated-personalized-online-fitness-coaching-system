package com.fitnesscoaching.platform.common.security;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        String errorCode = "UNAUTHORIZED";
        String message = "Authentication is required to access this resource.";

        if (authException instanceof InvalidBearerTokenException ibte) {
            String detail = ibte.getMessage() != null ? ibte.getMessage().toLowerCase() : "";
            if (detail.contains("expired")) {
                errorCode = "AUTH_TOKEN_EXPIRED";
                message = "Authentication token has expired.";
            } else {
                errorCode = "UNAUTHORIZED";
                message = "Authentication token is invalid.";
            }
        }

        String requestId = resolveRequestId(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                errorCode,
                message,
                Instant.now(clock),
                requestId,
                Collections.emptyList()
        );

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(RequestIdHolder.REQUEST_ID_HEADER, requestId);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdHolder.MDC_KEY);
        if (attr instanceof String reqId && !reqId.isBlank()) {
            return reqId;
        }
        String header = request.getHeader(RequestIdHolder.REQUEST_ID_HEADER);
        return RequestIdFilter.sanitizeOrGenerateRequestId(header);
    }
}
