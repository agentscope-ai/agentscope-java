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

package io.agentscope.core.a2a.agent.hitl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result supplied by an external executor for a pending schema-only tool. */
@JsonTypeName("external-tool-response")
public record A2aExternalToolResponse(
        String toolCallId,
        ToolResultState state,
        List<ContentBlock> outputBlocks,
        Map<String, Object> metadata)
        implements A2aHitlResponse {

    public A2aExternalToolResponse {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        state = Objects.requireNonNull(state, "state must not be null");
        outputBlocks = HitlValueCopies.copyContentBlocks(outputBlocks);
        metadata =
                metadata == null
                        ? Map.of()
                        : HitlValueCopies.immutableJsonMap(
                                MessageConvertUtil.stripSensitiveMetadata(metadata));
    }

    @Override
    public List<ContentBlock> outputBlocks() {
        return HitlValueCopies.copyContentBlocks(outputBlocks);
    }

    @Override
    @JsonProperty("responseType")
    public String responseType() {
        return "external-tool-response";
    }
}
