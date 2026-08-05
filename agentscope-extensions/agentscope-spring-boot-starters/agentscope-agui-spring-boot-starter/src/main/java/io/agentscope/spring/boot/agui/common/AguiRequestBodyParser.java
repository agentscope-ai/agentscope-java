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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;

/**
 * Parses AG-UI HTTP request bodies with AgentScope {@link JsonUtils} (Jackson 2).
 *
 * <p>Spring Boot 4 defaults to Jackson 3 for {@code @RequestBody} / {@code bodyToMono(T)}.
 * Jackson 3 does not honor Jackson-2 databind annotations such as {@code @JsonDeserialize}
 * on {@code MessageContent}, which leads to {@code Type definition error}. Decoding the
 * raw JSON string via {@link JsonUtils} keeps custom AG-UI deserializers working.
 *
 * @author dengyoutao
 * @since 2026-08-05
 */
public final class AguiRequestBodyParser {

    private AguiRequestBodyParser() {}

    /**
     * Deserialize a JSON request body into {@link RunAgentInput}.
     *
     * @param body raw JSON request body
     * @return parsed run input
     * @throws io.agentscope.core.util.JsonException if the body is not valid AG-UI JSON
     */
    public static RunAgentInput parseRunAgentInput(String body) {
        return JsonUtils.getJsonCodec().fromJson(body, RunAgentInput.class);
    }
}
