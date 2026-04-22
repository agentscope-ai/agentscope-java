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

package io.agentscope.examples.bobatea.supervisor.tools;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class A2aAgentTools {

    private final ObjectProvider<A2aAgent> consultAgentProvider;

    private final ObjectProvider<A2aAgent> businessAgentProvider;

    public A2aAgentTools(
            @Qualifier("consultAgent") ObjectProvider<A2aAgent> consultAgentProvider,
            @Qualifier("businessAgent") ObjectProvider<A2aAgent> businessAgentProvider) {
        this.consultAgentProvider = consultAgentProvider;
        this.businessAgentProvider = businessAgentProvider;
    }

    @Tool(
            description =
                    "Agent for handling consultation-related requests, can process all"
                        + " consultation-related requests, requires passing the complete context in"
                        + " the context parameter")
    public String callConsultAgent(
            @ToolParam(name = "context", description = "Complete context") String context,
            @ToolParam(name = "userId", description = "User's UserId") String userId) {
        // Since A2A extension's metadata support is not yet complete, temporarily pass userId
        // through the msg itself
        context = "<userId>" + userId + "</userId>" + context;
        Msg msg = Msg.builder().content(TextBlock.builder().text(context).build()).build();
        A2aAgent consultAgent = consultAgentProvider.getObject();
        return combineAgentResponse(consultAgent.call(msg).block());
    }

    @Tool(
            description =
                    "Agent for handling complaints and order-related requests, can process all"
                            + " complaint and order-related requests, requires passing the complete"
                            + " context in the context parameter")
    public String callBusinessAgent(
            @ToolParam(name = "context", description = "Complete context") String context,
            @ToolParam(name = "userId", description = "User's UserId") String userId) {
        // Since A2A extension's metadata support is not yet complete, temporarily pass userId
        // through the msg itself
        context = "<userId>" + userId + "</userId>" + context;
        Msg msg = Msg.builder().content(TextBlock.builder().text(context).build()).build();
        A2aAgent businessAgent = businessAgentProvider.getObject();
        return combineAgentResponse(businessAgent.call(msg).block());
    }

    private String combineAgentResponse(Msg responseMsg) {
        if (null == responseMsg) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        responseMsg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .forEach(block -> result.append(((TextBlock) block).getText()));
        return result.toString();
    }
}
