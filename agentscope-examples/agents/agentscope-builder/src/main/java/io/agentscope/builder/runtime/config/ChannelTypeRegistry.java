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
package io.agentscope.builder.runtime.config;

import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.builder.runtime.channel.dingtalk.DingTalkChannel;
import io.agentscope.builder.runtime.channel.feishu.FeishuChannel;
import io.agentscope.builder.runtime.channel.github.GitHubChannel;
import io.agentscope.builder.runtime.channel.gitlab.GitLabChannel;
import io.agentscope.builder.runtime.channel.wecom.WeComChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link ChannelFactory} implementations keyed by {@code type}. Looked up by
 * {@link io.agentscope.builder.runtime.BuilderBootstrap} when auto-instantiating channels from
 * {@code agentscope.json}.
 *
 * <p>Built-in types: {@code chatui}, {@code dingtalk}, {@code wecom}, {@code feishu},
 * {@code github}, {@code gitlab}. Callers may {@link #register(String, ChannelFactory) register}
 * additional types before {@link io.agentscope.builder.runtime.BuilderBootstrap.Builder#build()}
 * runs.
 */
public final class ChannelTypeRegistry {

    private static final ConcurrentHashMap<String, ChannelFactory> FACTORIES =
            new ConcurrentHashMap<>();

    static {
        register(
                ChatUiChannel.CHANNEL_ID,
                (channelId, routing, properties) -> ChatUiChannel.create(routing));
        register(DingTalkChannel.TYPE, DingTalkChannel::fromProperties);
        register(WeComChannel.TYPE, WeComChannel::fromProperties);
        register(FeishuChannel.TYPE, FeishuChannel::fromProperties);
        register(GitHubChannel.TYPE, GitHubChannel::fromProperties);
        register(GitLabChannel.TYPE, GitLabChannel::fromProperties);
    }

    private ChannelTypeRegistry() {}

    /**
     * Registers (or replaces) a factory under {@code typeId}. Returns the previously registered
     * factory, if any.
     */
    public static ChannelFactory register(String typeId, ChannelFactory factory) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(factory, "factory");
        return FACTORIES.put(typeId, factory);
    }

    /** Returns the factory registered for {@code typeId}, if any. */
    public static Optional<ChannelFactory> get(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FACTORIES.get(typeId));
    }

    /** Returns an immutable snapshot of registered type ids. */
    public static Set<String> registeredTypes() {
        return Map.copyOf(FACTORIES).keySet();
    }
}
