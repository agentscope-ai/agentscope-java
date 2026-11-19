/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.common;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-peer sliding-window throttle for inbound channel events. Protects the bot from being looped
 * into runaway interaction with another bot (or a stuck user/script).
 *
 * <p>Defaults follow OpenClaw: {@code 20} events / {@code 60s} window. Once tripped the peer is
 * placed in a {@code 60s} cooldown during which {@link #allow(String)} returns {@code false}.
 *
 * <p>Tracked peers do not accumulate forever: a peer with no recorded event for longer than
 * {@code windowMillis + cooldownMillis} is evicted (by then its sliding window is empty and any
 * cooldown has expired, so the entry carries no information). Sweeping is opportunistic — no
 * background thread is started: the first {@link #allow(String)} call after each
 * {@code windowMillis + cooldownMillis} period sweeps idle peers, so a peer is reclaimed within
 * roughly one further period after going idle. {@link #evictIdlePeers()} offers the same sweep
 * explicitly. This class is thread-safe.
 */
public final class BotLoopGuard {

    private final int maxEventsPerWindow;
    private final long windowMillis;
    private final long cooldownMillis;

    private final ConcurrentHashMap<String, PeerState> states = new ConcurrentHashMap<>();
    private volatile long lastSweepMs;

    public BotLoopGuard() {
        this(20, 60_000L, 60_000L);
    }

    public BotLoopGuard(int maxEventsPerWindow, long windowMillis, long cooldownMillis) {
        if (maxEventsPerWindow <= 0 || windowMillis <= 0 || cooldownMillis <= 0) {
            throw new IllegalArgumentException("all bounds must be positive");
        }
        this.maxEventsPerWindow = maxEventsPerWindow;
        this.windowMillis = windowMillis;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Records an event for {@code peerKey} and returns {@code true} when the peer is within budget
     * (caller may proceed). When the per-window cap is exceeded, the peer enters cooldown and this
     * method returns {@code false} until the cooldown elapses.
     */
    public boolean allow(String peerKey) {
        if (peerKey == null || peerKey.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        maybeSweep(now);
        PeerState state = states.computeIfAbsent(peerKey, k -> new PeerState(now));
        synchronized (state) {
            if (state.cooldownUntilMs > now) {
                return false;
            }
            // Drop events older than the window.
            while (!state.events.isEmpty() && now - state.events.peekFirst() > windowMillis) {
                state.events.pollFirst();
            }
            if (state.events.size() >= maxEventsPerWindow) {
                state.cooldownUntilMs = now + cooldownMillis;
                state.events.clear();
                state.lastEventMs = now;
                return false;
            }
            state.events.addLast(now);
            state.lastEventMs = now;
            return true;
        }
    }

    /**
     * Sweeps idle peers piggybacked on {@link #allow(String)} calls: the first call after each
     * {@code windowMillis + cooldownMillis} period sweeps. No background thread is started;
     * concurrent callers may sweep redundantly, which is harmless.
     */
    private void maybeSweep(long now) {
        if (now - lastSweepMs >= windowMillis + cooldownMillis) {
            lastSweepMs = now;
            evictIdlePeers(now);
        }
    }

    /** Returns {@code true} when {@code peerKey} is currently in cooldown. */
    public boolean isCoolingDown(String peerKey) {
        PeerState state = states.get(peerKey);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.cooldownUntilMs > System.currentTimeMillis();
        }
    }

    /** Returns the number of currently tracked peers; mostly for tests/observability. */
    public int trackedPeers() {
        return states.size();
    }

    /**
     * Removes peers that have recorded no event for longer than {@code windowMillis +
     * cooldownMillis}; by then the sliding window is empty and any cooldown has expired, so an
     * evicted peer simply restarts with a fresh window on its next event. A newly created entry
     * carries its creation time as its last-event stamp, so it can never be mistaken for idle
     * here.
     *
     * @return the number of peers removed
     */
    public int evictIdlePeers() {
        return evictIdlePeers(System.currentTimeMillis());
    }

    private int evictIdlePeers(long now) {
        long idleThreshold = windowMillis + cooldownMillis;
        int removed = 0;
        for (Map.Entry<String, PeerState> entry : states.entrySet()) {
            PeerState state = entry.getValue();
            synchronized (state) {
                // Conditional remove: only drops the entry if it still maps to this exact,
                // validated-idle object — a concurrent re-acquire cannot lose its fresh state.
                if (now - state.lastEventMs > idleThreshold
                        && states.remove(entry.getKey(), state)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static final class PeerState {
        final Deque<Long> events = new ArrayDeque<>();
        long cooldownUntilMs;
        long lastEventMs;

        PeerState(long now) {
            // Stamped at creation: a newly created entry can never satisfy the idle check.
            this.lastEventMs = now;
        }
    }
}
