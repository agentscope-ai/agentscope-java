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
package io.agentscope.builder.runtime.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-channel routing configuration: default agent fallback, DM session scope, and ordered binding
 * rules evaluated by {@link ChannelRouter}.
 *
 * <p>Used by {@link Channel} implementations to parameterize routing without hard-coding agent ids
 * or session shapes in adapter code.
 *
 * @param channelId the logical channel identifier this config applies to (must match {@link
 *     Channel#channelId()})
 * @param defaultAgentId fallback agent id when no binding matches; if null the global default
 *     agent (registered via {@link io.agentscope.builder.runtime.gateway.Gateway#bindMainAgent}) is used
 * @param dmScope controls how DM ({@link PeerKind#DIRECT}) sessions are keyed; defaults to {@link
 *     DmScope#MAIN}
 * @param bindings ordered list of binding rules; evaluated in list order within each priority tier
 */
public record ChannelConfig(
        String channelId, String defaultAgentId, DmScope dmScope, List<ChannelBinding> bindings) {

    public ChannelConfig {
        Objects.requireNonNull(channelId, "channelId");
        dmScope = dmScope != null ? dmScope : DmScope.defaultScope();
        bindings = bindings != null ? List.copyOf(bindings) : List.of();
    }

    /** Minimal config: channel id only, using global agent default and {@link DmScope#MAIN}. */
    public static ChannelConfig of(String channelId) {
        return new ChannelConfig(channelId, null, DmScope.MAIN, List.of());
    }

    /** Config with explicit default agent (no binding rules). */
    public static ChannelConfig of(String channelId, String defaultAgentId) {
        return new ChannelConfig(channelId, defaultAgentId, DmScope.MAIN, List.of());
    }

    /** Returns a builder for constructing channel configs fluently. */
    public static Builder builder(String channelId) {
        return new Builder(channelId);
    }

    public static final class Builder {
        private final String channelId;
        private String defaultAgentId;
        private DmScope dmScope = DmScope.MAIN;
        private final List<ChannelBinding> bindings = new ArrayList<>();

        private Builder(String channelId) {
            this.channelId = Objects.requireNonNull(channelId, "channelId");
        }

        public Builder defaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
            return this;
        }

        public Builder dmScope(DmScope dmScope) {
            this.dmScope = dmScope;
            return this;
        }

        public Builder binding(ChannelBinding binding) {
            this.bindings.add(Objects.requireNonNull(binding, "binding"));
            return this;
        }

        public Builder bindings(List<ChannelBinding> bindings) {
            if (bindings != null) {
                this.bindings.addAll(bindings);
            }
            return this;
        }

        public ChannelConfig build() {
            return new ChannelConfig(channelId, defaultAgentId, dmScope, bindings);
        }
    }
}
