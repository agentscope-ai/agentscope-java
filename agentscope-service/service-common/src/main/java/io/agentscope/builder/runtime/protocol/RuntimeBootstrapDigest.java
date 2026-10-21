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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;

/** Hashes immutable admission content; refreshed transport deadlines and traces are not content. */
public final class RuntimeBootstrapDigest {
    private RuntimeBootstrapDigest() {}

    public static String of(ObjectMapper mapper, RuntimeSessionBootstrap bootstrap) {
        var context = bootstrap.context();
        var content = new LinkedHashMap<String, Object>();
        content.put("tenant", context.tenant());
        content.put("namespace", context.namespace());
        content.put("applicationId", context.applicationId());
        content.put("sessionId", context.sessionId());
        content.put("actor", context.actor());
        content.put("runtimeKind", bootstrap.runtimeKind());
        content.put("configurationRevision", bootstrap.configurationRevision());
        content.put("configuration", bootstrap.configuration());
        return RuntimePayloadDigest.of(mapper, content);
    }

    public static String configurationRevision(ObjectMapper mapper, Object configuration) {
        return RuntimePayloadDigest.of(mapper, configuration);
    }
}
