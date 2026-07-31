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
 *
 * <p>When denied ({@code confirmed == false}), an optional {@link #message} can override the
 * default tool-result text ({@code "Permission denied by user"}).
 */
public class ConfirmResult {

    private final boolean confirmed;
    private final ToolUseBlock toolCall;
    private final List<PermissionRule> rules;
    private final String message;

    @JsonCreator
    public ConfirmResult(
            @JsonProperty("confirmed") boolean confirmed,
            @JsonProperty("toolCall") ToolUseBlock toolCall,
            @JsonProperty("rules") List<PermissionRule> rules,
            @JsonProperty("message") String message) {
        this.confirmed = confirmed;
        this.toolCall = toolCall;
        this.rules = rules;
        this.message = message;
    }

    /** Convenience constructor without a custom deny message. */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall, List<PermissionRule> rules) {
        this(confirmed, toolCall, rules, null);
    }

    /** Convenience constructor without rules or a custom deny message. */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall) {
        this(confirmed, toolCall, null, null);
    }

    /**
     * Convenience constructor with a custom deny message and no rules.
     *
     * <p>Useful when the user rejects a tool call and wants the model to see a specific reason.
     */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall, String message) {
        this(confirmed, toolCall, null, message);
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

    /**
     * Optional text used as the denied tool-result content when {@link #confirmed} is false.
     *
     * <p>When null or blank, the agent falls back to {@code "Permission denied by user"}.
     *
     * @return custom deny message, or null
     */
    public String getMessage() {
        return message;
    }
}
