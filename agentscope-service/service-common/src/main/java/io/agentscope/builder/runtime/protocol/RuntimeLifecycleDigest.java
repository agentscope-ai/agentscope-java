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
package io.agentscope.builder.runtime.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Sorts record fields as maps so Java and Go acknowledge the same lifecycle intent. */
public final class RuntimeLifecycleDigest {
    private RuntimeLifecycleDigest() {}

    public static String of(ObjectMapper mapper, RuntimeSessionLifecycle request) {
        Map<String, Object> content =
                mapper.convertValue(request, new TypeReference<Map<String, Object>>() {});
        return RuntimePayloadDigest.of(mapper, content);
    }
}
