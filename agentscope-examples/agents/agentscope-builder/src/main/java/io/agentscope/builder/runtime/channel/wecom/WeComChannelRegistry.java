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
package io.agentscope.builder.runtime.channel.wecom;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide lookup table from {@code channelId} to {@link WeComChannel} instance. Used by
 * {@link WeComCallbackController} to dispatch URL-routed requests onto the correct channel.
 *
 * <p>Channels register themselves in {@link WeComChannel#start()} and remove on
 * {@link WeComChannel#stop()}. The controller obtains the same singleton via
 * {@link #instance()} (no Spring wiring required), since the channel is created by
 * {@link io.agentscope.builder.runtime.config.ChannelTypeRegistry}'s static factory and the controller
 * by Spring's component scan — neither knows about the other ahead of time.
 */
public final class WeComChannelRegistry {

    private static final WeComChannelRegistry INSTANCE = new WeComChannelRegistry();

    private final ConcurrentHashMap<String, WeComChannel> channels = new ConcurrentHashMap<>();

    private WeComChannelRegistry() {}

    /** Returns the process-wide singleton instance. */
    public static WeComChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(WeComChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public WeComChannel get(String channelId) {
        return channels.get(channelId);
    }
}
