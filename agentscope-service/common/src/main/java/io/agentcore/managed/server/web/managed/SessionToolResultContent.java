/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.managed;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Tool results remain typed content through admission, persistence and model continuation. */
public final class SessionToolResultContent {
    private SessionToolResultContent() {}

    public static List<ContentBlock> blocks(Map<?, ?> answer) {
        if (answer.containsKey("output") == answer.containsKey("content"))
            throw invalid("Supply exactly one of output or content");
        if (answer.get("output") instanceof String output) {
            if (output.length() > 1048576) throw invalid("Tool output exceeds 1 MiB");
            return List.of(TextBlock.builder().text(output).build());
        }
        if (!(answer.get("content") instanceof List<?> content) || content.size() > 1000)
            throw invalid("Tool content requires a content-block array");
        if (JsonUtils.getJsonCodec().toJson(content).getBytes(StandardCharsets.UTF_8).length
                > 1048576) throw invalid("Tool content exceeds 1 MiB");
        var result = new ArrayList<ContentBlock>();
        for (Object item : content) {
            if (!(item instanceof Map<?, ?> block)
                    || !Set.of("text", "image").contains(String.valueOf(block.get("type"))))
                throw invalid("Unsupported tool content type");
            if ("text".equals(block.get("type")) && !(block.get("text") instanceof String))
                throw invalid("Text block requires text");
            if ("image".equals(block.get("type")) && !(block.get("source") instanceof Map<?, ?>))
                throw invalid("Image block requires source");
            try {
                result.add(JsonUtils.getJsonCodec().convertValue(block, ContentBlock.class));
            } catch (IllegalArgumentException error) {
                throw invalid("Invalid tool content block");
            }
        }
        return List.copyOf(result);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
