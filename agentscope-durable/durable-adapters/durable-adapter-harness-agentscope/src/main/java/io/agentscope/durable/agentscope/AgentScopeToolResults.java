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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Map;

/** Native/MCP result envelopes retain content and metadata instead of flattening them to text. */
final class AgentScopeToolResults {
    private AgentScopeToolResults() {}

    static ToolResultBlock convert(ToolUseBlock call, ExecutionResult result) {
        return convert(call, result, 0);
    }

    private static ToolResultBlock convert(ToolUseBlock call, ExecutionResult result, int depth) {
        if (depth > 8)
            throw new DurableException(
                    DurableException.Code.INVALID, "Nested result depth exceeded");
        var value = result.result();
        ToolResultBlock block;
        if (value != null && "mcp.result.v1".equals(value.path("format").asText())) {
            var raw = value.required("result");
            var mcp = DurableJson.convert(raw, McpSchema.CallToolResult.class);
            block =
                    ToolResultBlock.of(
                                    McpContentConverter.convertContentList(mcp.content()),
                                    Map.of(
                                            "mcp",
                                            JsonUtils.getJsonCodec().convertValue(raw, Map.class)))
                            .withState(
                                    Boolean.TRUE.equals(mcp.isError())
                                            ? ToolResultState.ERROR
                                            : ToolResultState.SUCCESS);
        } else if (value != null && "durable.batch.v1".equals(value.path("format").asText())) {
            var results = value.path("results");
            if (!results.isArray() || results.size() > 128)
                throw new DurableException(DurableException.Code.INVALID, "Invalid batch result");
            var content = new ArrayList<ContentBlock>();
            var children = new ArrayList<ToolResultBlock>();
            for (var entry : results) {
                var childCall =
                        ToolUseBlock.builder()
                                .id(entry.required("callId").asText())
                                .name(entry.path("name").asText(call.getName()))
                                .input(Map.of())
                                .build();
                var child =
                        convert(
                                childCall,
                                DurableJson.convert(
                                        entry.required("result"), ExecutionResult.class),
                                depth + 1);
                content.add(TextBlock.builder().text("Tool call " + child.getId() + ":").build());
                content.addAll(child.getOutput());
                children.add(child);
            }
            block = ToolResultBlock.of(content, Map.of("batch", children));
        } else if (value != null
                && "agentscope.tool-result.v1".equals(value.path("format").asText())) {
            block =
                    JsonUtils.getJsonCodec()
                            .convertValue(value.required("result"), ToolResultBlock.class);
            if (block.isSuspended())
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Terminal tool result cannot remain suspended");
        } else if (result.error() != null) block = ToolResultBlock.error(result.error());
        else
            block =
                    ToolResultBlock.text(
                            value == null || value.isNull()
                                    ? ""
                                    : value.isTextual() ? value.asText() : value.toString());
        if (result.state() == ExecutionState.CANCELLED)
            block = block.withState(ToolResultState.INTERRUPTED);
        else if (result.state() == ExecutionState.FAILED
                && block.getState() != ToolResultState.DENIED)
            block = block.withState(ToolResultState.ERROR);
        else if (result.state() == ExecutionState.SUCCEEDED
                && block.getState() == ToolResultState.RUNNING)
            // Native factories default to RUNNING because they also build streaming chunks.
            // A settled Hands result is complete, while an explicit native error stays an error.
            block = block.withState(ToolResultState.SUCCESS);
        return block.withIdAndName(call.getId(), call.getName());
    }
}
