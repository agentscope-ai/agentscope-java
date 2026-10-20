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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseToolSearchCall;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.search.ToolSearchTool;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;

/** Client search calls execute locally; their native history must not become function calls. */
final class ResponsesToolSearchHelper {
    static final String CALL_METADATA = "openai.clientToolSearchCall";

    private ResponsesToolSearchHelper() {}

    static boolean isClientSearch(ResponseOutputItem item) {
        return item.isToolSearchCall()
                && ResponseToolSearchCall.Execution.CLIENT.equals(
                        item.asToolSearchCall().execution());
    }

    static ToolUseBlock decode(ResponseOutputItem item) {
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
                    .metadata(
                            Map.of(
                                    CALL_METADATA,
                                    ObjectMappers.jsonMapper().writeValueAsString(item)))
                    .build();
        } catch (Exception e) {
            throw new OpenAIOfficialModelException(
                    "Cannot decode client tool search call", e, null);
        }
    }

    static ResponseInputItem restore(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, ResponseInputItem.class);
        } catch (Exception e) {
            throw new OpenAIOfficialModelException(
                    "Cannot restore client tool search call", e, null);
        }
    }
}
