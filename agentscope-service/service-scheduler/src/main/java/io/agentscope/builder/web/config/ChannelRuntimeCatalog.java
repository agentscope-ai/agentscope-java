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
package io.agentscope.builder.web.config;

import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory snapshot of channel configs last fetched from the control plane. Used by {@link
 * io.agentscope.builder.runtime.SchedulerGateway} to resolve the Builder owner of an IM channel
 * when bridging inbound turns into managed sessions.
 */
@Component
public class ChannelRuntimeCatalog {

    private final ConcurrentHashMap<String, ChannelConfigEntry> entries = new ConcurrentHashMap<>();

    /** Replaces the catalog with {@code configs} (null-safe). */
    public void replaceAll(Map<String, ChannelConfigEntry> configs) {
        entries.clear();
        if (configs != null) {
            entries.putAll(configs);
        }
    }

    /** Returns the config for {@code channelId}, or {@code null}. */
    public ChannelConfigEntry get(String channelId) {
        if (channelId == null) {
            return null;
        }
        return entries.get(channelId);
    }

    /** Builder owner id for the channel, or {@code null} when unknown. */
    public String ownerId(String channelId) {
        ChannelConfigEntry e = get(channelId);
        return e != null ? e.getOwnerId() : null;
    }

    /** DmScope string for the channel (may be null / blank → treat as MAIN). */
    public String dmScope(String channelId) {
        ChannelConfigEntry e = get(channelId);
        return e != null ? e.getDmScope() : null;
    }

    /** Unmodifiable view of the current catalog. */
    public Map<String, ChannelConfigEntry> snapshot() {
        return Collections.unmodifiableMap(Map.copyOf(entries));
    }
}
