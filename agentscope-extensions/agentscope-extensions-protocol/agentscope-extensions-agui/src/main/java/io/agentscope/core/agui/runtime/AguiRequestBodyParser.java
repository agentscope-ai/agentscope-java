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
package io.agentscope.core.agui.runtime;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import java.util.Objects;

/**
 * Parses AG-UI request bodies with AgentScope's JSON codec.
 *
 * <p>The default AgentScope codec is Jackson 2. Keeping this conversion at the AG-UI boundary
 * avoids selecting Spring Boot 4's Jackson 3 codec for AG-UI's Jackson 2-annotated models, while
 * leaving the application's other HTTP converters unchanged.
 *
 * <p>The Spring Boot starter exposes this type as a bean and provides a default instance via
 * {@code @ConditionalOnMissingBean}. Applications can register their own {@code
 * AguiRequestBodyParser} bean to customize request body conversion.
 */
public class AguiRequestBodyParser {

    /**
     * Parses a JSON request body into {@link RunAgentInput}.
     *
     * @param body the raw JSON request body
     * @return the parsed AG-UI input
     */
    public RunAgentInput parse(String body) {
        Objects.requireNonNull(body, "body cannot be null");
        return JsonUtils.getJsonCodec().fromJson(body, RunAgentInput.class);
    }
}
