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
package io.agentscope.core.tool.search;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;

/** Immutable search input; candidates already respect tool groups and request overrides. */
public record ToolSearchRequest(
        List<ToolSchema> candidates, List<Msg> recentMessages, String query, int limit) {
    public ToolSearchRequest {
        candidates = List.copyOf(candidates);
        recentMessages = List.copyOf(recentMessages);
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }
}
