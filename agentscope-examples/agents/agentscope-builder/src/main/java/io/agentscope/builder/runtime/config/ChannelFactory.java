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

import io.agentscope.builder.runtime.channel.Channel;
import io.agentscope.builder.runtime.channel.ChannelConfig;
import java.util.Map;

/**
 * Constructs a {@link Channel} instance from the resolved routing config and the raw provider
 * properties supplied under {@code channels.<id>.properties} in {@code agentscope.json}.
 *
 * <p>Registered in {@link ChannelTypeRegistry} under a type id (e.g. {@code "chatui"},
 * {@code "dingtalk"}, {@code "wecom"}).
 */
@FunctionalInterface
public interface ChannelFactory {

    /**
     * Build the channel.
     *
     * @param channelId the logical channel id (the JSON key under {@code channels})
     * @param routing the parsed routing config (defaultAgentId, dmScope, bindings)
     * @param properties provider-specific properties from {@code channels.<id>.properties}; never
     *     null but may be empty
     * @return a fully constructed channel ready for {@code init(Gateway)} + {@code start()}
     */
    Channel create(String channelId, ChannelConfig routing, Map<String, Object> properties);
}
