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
package com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.node;

import com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.tools.SqlTools;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * Uses AgentScope Model to force a tool call for sql_db_schema. Builds a ReActAgent with
 * only getSchema tool, runs it with context from state (question + available tables),
 * then converts the response to Spring AI messages for graph state.
 */
public class CallGetSchemaNode implements NodeAction {

    private static final String GET_SCHEMA_PROMPT =
            """
            You must call the sql_db_schema tool with a comma-separated list of table names.
            Use the available tables from the user message. Do not explain, only output the tool call.
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Model model;
    private final SqlTools sqlTools;

    public CallGetSchemaNode(Model model, SqlTools sqlTools) {
        this.model = model;
        this.sqlTools = sqlTools;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
        String question = (String) state.value("question").orElse("");

        String userText = buildUserText(messages, question);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(sqlTools);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("get_schema_caller")
                        .sysPrompt(GET_SCHEMA_PROMPT)
                        .model(model)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userText).build();
        Msg response = agent.call(userMsg).block();
        if (response == null) {
            return Map.of("messages", List.<Message>of());
        }

        List<ToolUseBlock> toolUses = response.getContentBlocks(ToolUseBlock.class);
        if (toolUses.isEmpty()) {
            return Map.of(
                    "messages",
                    List.of(toAssistantMessage(response)),
                    "llm_response",
                    toAssistantMessage(response));
        }

        AssistantMessage assistantMessage = toAssistantMessage(response, toolUses);
        return Map.of("messages", List.of(assistantMessage), "llm_response", assistantMessage);
    }

    private String buildUserText(List<Message> messages, String question) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String text = m.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        sb.append("User question: ").append(question);
        return sb.toString();
    }

    private AssistantMessage toAssistantMessage(Msg msg) {
        return new AssistantMessage(msg.getTextContent() != null ? msg.getTextContent() : "");
    }

    private AssistantMessage toAssistantMessage(Msg msg, List<ToolUseBlock> toolUses) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ToolUseBlock tu : toolUses) {
            String argsJson;
            try {
                argsJson =
                        JSON.writeValueAsString(tu.getInput() != null ? tu.getInput() : Map.of());
            } catch (JsonProcessingException e) {
                argsJson = "{}";
            }
            toolCalls.add(
                    new AssistantMessage.ToolCall(tu.getId(), "function", tu.getName(), argsJson));
        }
        return AssistantMessage.builder()
                .content(msg.getTextContent() != null ? msg.getTextContent() : "")
                .toolCalls(toolCalls)
                .build();
    }
}
