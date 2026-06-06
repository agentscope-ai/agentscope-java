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
package io.agentscope.builder.runtime.channel.feishu;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide lookup table from {@code channelId} to {@link FeishuChannel}. Used by
 * {@link FeishuCallbackController} to dispatch URL-routed requests onto the correct channel,
 * mirroring the pattern in
 * {@link io.agentscope.builder.runtime.channel.wecom.WeComChannelRegistry}.
 */
public final class FeishuChannelRegistry {

    private static final FeishuChannelRegistry INSTANCE = new FeishuChannelRegistry();

    private final ConcurrentHashMap<String, FeishuChannel> channels = new ConcurrentHashMap<>();

    private FeishuChannelRegistry() {}

    /** Returns the process-wide singleton instance. */
    public static FeishuChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(FeishuChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public FeishuChannel get(String channelId) {
        return channels.get(channelId);
    }
}
