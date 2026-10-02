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
package io.agentscope.extensions.channel.dingtalk;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide lookup table from {@code channelId} to {@link DingTalkChannel} instance. Used by
 * {@link DingTalkCallbackController} to dispatch URL-routed requests onto the correct channel.
 *
 * <p>Channels running in http callback mode register themselves in {@link
 * DingTalkChannel#start()} and remove on {@link DingTalkChannel#stop()}. The controller obtains the
 * same singleton via {@link #instance()} (no Spring wiring required), since the channel is created
 * by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}'s static factory and the
 * controller by Spring's component scan — neither knows about the other ahead of time.
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap}.
 */
public final class DingTalkChannelRegistry {

    private static final DingTalkChannelRegistry INSTANCE = new DingTalkChannelRegistry();

    private final ConcurrentHashMap<String, DingTalkChannel> channels = new ConcurrentHashMap<>();

    private DingTalkChannelRegistry() {}

    /** Returns the process-wide singleton instance. */
    public static DingTalkChannelRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers a channel under its channel id. Re-registering the same instance is a no-op; a
     * different instance already holding the id fails fast — silently replacing it would divert
     * the running channel's callbacks without a trace.
     */
    public void register(DingTalkChannel channel) {
        DingTalkChannel existing = channels.putIfAbsent(channel.channelId(), channel);
        if (existing != null && existing != channel) {
            throw new IllegalStateException(
                    "A DingTalk channel is already registered for id '"
                            + channel.channelId()
                            + "'");
        }
    }

    /**
     * Removes the registration for {@code channelId} only when it currently maps to {@code
     * expected}, so a stale instance shutting down cannot detach a successor's registration.
     */
    public void unregister(String channelId, DingTalkChannel expected) {
        channels.remove(channelId, Objects.requireNonNull(expected, "expected"));
    }

    /** Returns the channel registered for {@code channelId}, or {@code null} if none. */
    public DingTalkChannel get(String channelId) {
        return channels.get(channelId);
    }
}
