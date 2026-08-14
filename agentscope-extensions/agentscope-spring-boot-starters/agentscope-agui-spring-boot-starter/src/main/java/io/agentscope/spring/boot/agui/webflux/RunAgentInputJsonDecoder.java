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
package io.agentscope.spring.boot.agui.webflux;

import io.agentscope.core.agui.model.RunAgentInput;
import org.springframework.core.ResolvableType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.util.MimeType;

@SuppressWarnings("removal")
final class RunAgentInputJsonDecoder extends Jackson2JsonDecoder {

    @Override
    public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
        Class<?> resolvedType = elementType.resolve();
        return resolvedType != null
                && RunAgentInput.class.isAssignableFrom(resolvedType)
                && super.canDecode(elementType, mimeType);
    }
}
