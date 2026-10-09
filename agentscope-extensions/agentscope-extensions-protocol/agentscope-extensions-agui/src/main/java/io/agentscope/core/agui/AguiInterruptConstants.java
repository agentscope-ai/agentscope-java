/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui;

/** Constants for AG-UI interrupt reasons and AgentScope interrupt metadata. */
public final class AguiInterruptConstants {

    private AguiInterruptConstants() {}

    /** AG-UI interrupt reason for tool-bound decisions. */
    public static final String TOOL_CALL_INTERRUPT_REASON = "tool_call";

    /** AG-UI interrupt reason for structured input requests. */
    public static final String INPUT_REQUIRED_INTERRUPT_REASON = "input_required";

    /** Interrupt metadata key: AgentScope-specific interrupt kind. */
    public static final String METADATA_AGENTSCOPE_INTERRUPT_KIND = "agentscope.interruptKind";

    /** Interrupt metadata value for permission-mode tool confirmations. */
    public static final String INTERRUPT_KIND_PERMISSION_CONFIRM = "permission_confirm";

    /** Interrupt metadata key: the tool name. */
    public static final String METADATA_TOOL_NAME = "toolName";

    /** Interrupt metadata key: the parsed tool arguments. */
    public static final String METADATA_TOOL_INPUT = "toolInput";

    /** Interrupt metadata key: the tool arguments serialized as a JSON-object string. */
    public static final String METADATA_TOOL_CONTENT = "toolContent";

    /** Interrupt metadata key: the originating reply id. */
    public static final String METADATA_REPLY_ID = "replyId";
}
