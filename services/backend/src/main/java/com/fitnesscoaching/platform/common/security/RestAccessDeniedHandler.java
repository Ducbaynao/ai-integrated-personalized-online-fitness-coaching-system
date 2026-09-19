package com.fitnesscoaching.platform.common.security;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        String requestId = resolveRequestId(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                "ACCESS_DENIED",
                "Access is denied.",
                Instant.now(clock),
                requestId,
                Collections.emptyList()
        );

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
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
