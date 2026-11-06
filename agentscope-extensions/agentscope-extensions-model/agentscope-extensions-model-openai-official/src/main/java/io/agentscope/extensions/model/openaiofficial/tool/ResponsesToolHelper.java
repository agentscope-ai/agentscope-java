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
package io.agentscope.extensions.model.openaiofficial.tool;

import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseToolSearchCall;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.search.ToolSearchTool;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openaiofficial.OpenAIOfficialModelException;
import java.util.Map;

/** Client-executed Responses tool calls are decoded locally and replayed as native items. */
public final class ResponsesToolHelper {
    /** Metadata key marking a decoded client-executed tool search call. */
    public static final String CLIENT_TOOL_SEARCH_CALL_METADATA = "openai.clientToolSearchCall";

    private ResponsesToolHelper() {}

    public static boolean isClientToolSearch(ResponseOutputItem item) {
        return item.isToolSearchCall()
                && ResponseToolSearchCall.Execution.CLIENT.equals(
                        item.asToolSearchCall().execution());
    }

    public static ToolUseBlock decodeClientToolSearch(ResponseOutputItem item) {
        var call = item.asToolSearchCall();
        try {
            String arguments = ObjectMappers.jsonMapper().writeValueAsString(call._arguments());
            @SuppressWarnings("unchecked")
            Map<String, Object> input = JsonUtils.getJsonCodec().fromJson(arguments, Map.class);
            return ToolUseBlock.builder()
                    .id(call.callId().orElseThrow())
                    .name(ToolSearchTool.NAME)
                    .input(input)
                    .content(arguments)
                    .metadata(Map.of(CLIENT_TOOL_SEARCH_CALL_METADATA, true))
                    .build();
        } catch (Exception e) {
            throw new OpenAIOfficialModelException(
                    "Cannot decode client tool search call", e, null);
        }
    }

    public static ResponseInputItem restoreClientToolSearch(ToolUseBlock toolUse) {
        try {
            Object arguments =
                    toolUse.getInput() != null
                            ? toolUse.getInput()
                            : JsonUtils.getJsonCodec().fromJson(toolUse.getContent(), Map.class);
            return ResponseInputItem.ofToolSearchCall(
                    ResponseInputItem.ToolSearchCall.builder()
                            .callId(toolUse.getId())
                            .arguments(JsonValue.from(arguments != null ? arguments : Map.of()))
                            .execution(ResponseInputItem.ToolSearchCall.Execution.CLIENT)
                            .status(ResponseInputItem.ToolSearchCall.Status.COMPLETED)
                            .build());
        } catch (Exception e) {
            throw new OpenAIOfficialModelException(
                    "Cannot restore client tool search call", e, null);
        }
    }
}
