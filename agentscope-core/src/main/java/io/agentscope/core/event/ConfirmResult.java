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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;

/**
 * Represents the result of a single user confirmation decision for a tool call.
 *
 * <p>When confirmed, the caller may supply a modified {@link #toolCall} (allowing the user to
 * tweak input) and/or new {@link #rules} that the {@code PermissionEngine} should remember for
 * future calls — e.g. "always allow this command going forward".
 */
public class ConfirmResult {

    private final boolean confirmed;
    private final ToolUseBlock toolCall;
    private final List<PermissionRule> rules;

    @JsonCreator
    public ConfirmResult(
            @JsonProperty("confirmed") boolean confirmed,
            @JsonProperty("toolCall") ToolUseBlock toolCall,
            @JsonProperty("rules") List<PermissionRule> rules) {
        this.confirmed = confirmed;
        this.toolCall = toolCall;
        this.rules = rules;
    }

    /** Convenience constructor without rules. */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall) {
        this(confirmed, toolCall, null);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public ToolUseBlock getToolCall() {
        return toolCall;
    }

    /**
     * New permission rules to register with the engine when {@link #confirmed} is true. Each rule
     * extends the engine's allow / deny / ask tables based on its {@code behavior}.
     *
     * @return list of rules (may be null or empty)
     */
    public List<PermissionRule> getRules() {
        return rules;
    }
}
