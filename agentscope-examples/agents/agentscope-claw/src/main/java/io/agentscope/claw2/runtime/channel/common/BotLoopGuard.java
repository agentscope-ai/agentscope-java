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
package io.agentscope.claw2.runtime.channel.common;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-peer sliding-window throttle for inbound channel events. Protects the bot from being looped
 * into runaway interaction with another bot (or a stuck user/script).
 *
 * <p>Defaults follow OpenClaw: {@code 20} events / {@code 60s} window. Once tripped the peer is
 * placed in a {@code 60s} cooldown during which {@link #allow(String)} returns {@code false}.
 */
public final class BotLoopGuard {

    private final int maxEventsPerWindow;
    private final long windowMillis;
    private final long cooldownMillis;

    private final ConcurrentHashMap<String, PeerState> states = new ConcurrentHashMap<>();

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
        PeerState state = states.computeIfAbsent(peerKey, k -> new PeerState());
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
                return false;
            }
            state.events.addLast(now);
            return true;
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

    private static final class PeerState {
        final Deque<Long> events = new ArrayDeque<>();
        long cooldownUntilMs;
    }
}
