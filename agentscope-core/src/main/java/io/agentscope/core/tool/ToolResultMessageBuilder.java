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
package io.agentscope.core.tool;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * Utility class for building tool result messages from ToolResultBlock objects.
 *
 * This class handles the conversion of ToolResultBlock to Msg with ToolResultBlock,
 * setting proper id and name from the original tool call.
 * @hidden
 */
public class ToolResultMessageBuilder {

    /**
     * Build a tool result message from a ToolResultBlock and the original tool call.
     *
     * @param result The tool execution result
     * @param originalCall The original tool use block that triggered the execution
     * @param agentName The name of the agent creating this message
     * @return Msg containing the tool result
     */
    public static Msg buildToolResultMsg(
            ToolResultBlock result, ToolUseBlock originalCall, String agentName) {
        // Set id and name from original call
        ToolResultBlock resultWithIdAndName =
                result.withIdAndName(originalCall.getId(), originalCall.getName());

        return Msg.builder()
                .name(agentName)
                .role(MsgRole.TOOL)
                .content(resultWithIdAndName)
                .build();
    }
}
