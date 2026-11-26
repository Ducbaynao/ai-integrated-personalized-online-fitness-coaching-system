package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.modules.coaching.application.CoachingQrRateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CoachingQrRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int PRUNE_INTERVAL = 64;
    private final Clock clock;
    private final int maxRequestsPerMinute;
    private final Map<UUID, Deque<Instant>> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger checks = new AtomicInteger();

    public CoachingQrRateLimiter(
            Clock clock,
            @Value("${app.coaching.qr.resolve-rate-limit-per-minute:20}") int maxRequestsPerMinute) {
        this.clock = clock;
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
    }

    public void checkAndConsume(UUID actorId) {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(WINDOW);
        if (checks.incrementAndGet() % PRUNE_INTERVAL == 0 || buckets.size() > 1024) {
            pruneExpired(cutoff);
        }
        Deque<Instant> timestamps = buckets.computeIfAbsent(actorId, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequestsPerMinute) {
                Instant oldest = timestamps.peekFirst();
                long retryAfter = oldest == null ? 60L : Math.max(1L, Duration.between(now, oldest.plus(WINDOW)).toSeconds() + 1L);
                throw new CoachingQrRateLimitException(retryAfter);
            }
            timestamps.addLast(now);
        }
    }

    void pruneExpired(Instant cutoff) {
        for (UUID key : buckets.keySet()) {
            buckets.computeIfPresent(key, (ignored, deque) -> {
                synchronized (deque) {
                    while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                        deque.pollFirst();
                    }
                    return deque.isEmpty() ? null : deque;
                }
            });
        }
    }

    int trackedActorCount() {
        return buckets.size();
    }

    public void resetForTesting() {
        buckets.clear();
        checks.set(0);
    }
}
