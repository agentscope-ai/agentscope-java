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
package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Tool policy and result transformations configured for a pinned Harness definition. */
public record AgentScopeToolHooks(
        String version, Duration timeout, List<Policy> policies, List<Normalizer> normalizers) {
    static final AgentScopeToolHooks NONE =
            new AgentScopeToolHooks("none", Duration.ofSeconds(1), List.of(), List.of());

    public AgentScopeToolHooks {
        DurableJson.text(version, "tool hook version");
        if (timeout.toMillis() < 1)
            throw new IllegalArgumentException("Tool hook timeout required");
        policies = List.copyOf(policies);
        normalizers = List.copyOf(normalizers);
    }

    public enum Effect {
        ALLOW,
        CONFIRM,
        DENY
    }

    /** Optional replacement arguments must be a JSON object. Native permission checks still run. */
    public record Decision(Effect effect, JsonNode arguments) {
        public Decision {
            Objects.requireNonNull(effect);
            if (arguments != null && !arguments.isObject())
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            if (arguments != null) arguments = arguments.deepCopy();
        }

        public static Decision allow() {
            return new Decision(Effect.ALLOW, null);
        }
    }

    public record PolicyInput(ThreadSpec definition, String turnId, ToolUseBlock call) {}

    public record ResultInput(
            ThreadSpec definition,
            String turnId,
            String executionId,
            ToolUseBlock call,
            ToolResultBlock result) {}

    @FunctionalInterface
    public interface Policy {
        Decision evaluate(PolicyInput input) throws Exception;
    }

    /** Transforms model-visible content while retaining the execution state and evidence. */
    @FunctionalInterface
    public interface Normalizer {
        List<ContentBlock> normalize(ResultInput input) throws Exception;
    }
}
