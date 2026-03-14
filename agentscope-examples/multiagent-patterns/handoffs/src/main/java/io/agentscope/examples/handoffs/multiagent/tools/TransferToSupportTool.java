/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.handoffs.multiagent.tools;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.examples.handoffs.multiagent.state.AgentScopeStateConstants;
import org.springframework.ai.chat.model.ToolContext;

/**
 * AgentScope handoff tool for the sales agent: transfers the conversation to the support agent.
 * <p>
 * Uses AgentScope {@link io.agentscope.core.tool.Tool} with {@link ToolContext} (auto-injected).
 * Updates {@code active_agent} via {@link ToolContextHelper#getStateForUpdate(ToolContext)} so the
 * parent graph's conditional edges route to the support agent when the node completes.
 * <p>
 * Register via {@link io.agentscope.core.tool.Toolkit#registerTool(Object)}.
 */
public final class TransferToSupportTool {

    public static final String TOOL_NAME = "transfer_to_support";

    private TransferToSupportTool() {}

    @Tool(
            name = TOOL_NAME,
            description =
                    "Transfer the conversation to the support agent. Use when the customer asks"
                            + " about technical issues, troubleshooting, or account problems.")
    public String transferToSupport(ToolContext toolContext) {
        ToolContextHelper.getStateForUpdate(toolContext)
                .ifPresent(
                        update ->
                                update.put(
                                        AgentScopeStateConstants.ACTIVE_AGENT,
                                        AgentScopeStateConstants.SUPPORT_AGENT));
        return "Transferred to support agent from sales agent.";
    }

    public static TransferToSupportTool create() {
        return new TransferToSupportTool();
    }
}
