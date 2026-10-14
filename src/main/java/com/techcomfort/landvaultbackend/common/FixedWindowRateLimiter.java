package com.techcomfort.landvaultbackend.common;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window counts per client key, shared by the public
 * surfaces' rate-limit filters so there is one implementation of the counting.
 * Per-instance (each server keeps its own counts); fixed windows, so a burst
 * straddling a boundary can get up to twice the limit — fine at this scale.
 * <p>
 * The tracked-client table is bounded: past {@code maxTrackedClients} expired
 * windows are swept, and if it's still full it's cleared, erring towards
 * letting requests through rather than failing closed on everyone.
 */
@Slf4j
public class FixedWindowRateLimiter {

    private final String name;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(String name) {
        this.name = name;
    }

    /** Counts one request; returns the seconds to wait if it is over the limit, or 0 if it may proceed. */
    public long tryAcquire(String client, int limit, Duration window, int maxTrackedClients) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        if (windows.size() >= maxTrackedClients) {
            windows.values().removeIf(w -> now - w.start >= windowMillis);
            if (windows.size() >= maxTrackedClients) {
                log.warn("{} rate limiter tracked {} clients; clearing", name, windows.size());
                windows.clear();
            }
        }

        Window current = windows.compute(client, (key, existing) ->
                existing == null || now - existing.start >= windowMillis ? new Window(now) : existing);
        int count;
        synchronized (current) {
            count = ++current.count;
        }
        if (count <= limit) {
            return 0;
        }
        return Math.max(1, (current.start + windowMillis - now + 999) / 1000);
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
