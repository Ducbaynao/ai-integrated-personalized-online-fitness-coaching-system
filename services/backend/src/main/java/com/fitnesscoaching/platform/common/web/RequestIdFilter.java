package com.fitnesscoaching.platform.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_REQUEST_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawRequestId = request.getHeader(RequestIdHolder.REQUEST_ID_HEADER);
        String requestId = sanitizeOrGenerateRequestId(rawRequestId);

        MDC.put(RequestIdHolder.MDC_KEY, requestId);
        request.setAttribute(RequestIdHolder.MDC_KEY, requestId);
        response.setHeader(RequestIdHolder.REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdHolder.MDC_KEY);
        }
    }

    public static String sanitizeOrGenerateRequestId(String rawRequestId) {
        if (StringUtils.hasText(rawRequestId)) {
            String trimmed = rawRequestId.trim();
            if (SAFE_REQUEST_ID_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
