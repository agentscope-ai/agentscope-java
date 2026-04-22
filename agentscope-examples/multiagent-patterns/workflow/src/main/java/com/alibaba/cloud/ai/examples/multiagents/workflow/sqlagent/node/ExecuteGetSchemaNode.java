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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Executes the sql_db_schema tool when the previous message contains a tool call for it.
 * Appends tool response and assistant summary to messages. Replaces ToolNode for get_schema.
 */
public class ExecuteGetSchemaNode implements NodeAction {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqlTools sqlTools;

    public ExecuteGetSchemaNode(SqlTools sqlTools) {
        this.sqlTools = sqlTools;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
        Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (!(last instanceof AssistantMessage am)
                || am.getToolCalls() == null
                || am.getToolCalls().isEmpty()) {
            return Map.of();
        }

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            if (!"sql_db_schema".equals(tc.name())) {
                continue;
            }
            String tableNames = parseTableNames(tc.arguments());
            String result = sqlTools.getSchema(tableNames);
            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
        }
        if (responses.isEmpty()) {
            return Map.of();
        }

        ToolResponseMessage toolResponse =
                ToolResponseMessage.builder().responses(responses).build();
        AssistantMessage summary =
                new AssistantMessage("Schema retrieved. Proceed to generate the SQL query.");
        return Map.of("messages", List.of(toolResponse, summary));
    }

    @SuppressWarnings("unchecked")
    private static String parseTableNames(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> map = JSON.readValue(arguments, Map.class);
            Object v = map.get("tableNames");
            return v != null ? v.toString().trim() : "";
        } catch (Exception e) {
            return arguments.trim();
        }
    }
}
