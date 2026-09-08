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
package io.agentscope.examples.copilotkit.a2ui;

import java.util.List;
import java.util.Map;

/**
 * A generated A2UI surface, ready to be shipped to the browser.
 *
 * @param surfaceId the surface identifier shared by every operation in {@code operations}
 * @param catalogId the catalog the components come from
 * @param intent the natural-language request that produced the surface
 * @param generatedBy which composer produced it ({@code gemini}, {@code dashscope}, {@code
 *     fallback})
 * @param componentCount number of components in the rendered tree
 * @param operations the raw A2UI v0.9 server-to-client messages
 *
 */
public record A2uiSurface(
        String surfaceId,
        String catalogId,
        String intent,
        String generatedBy,
        int componentCount,
        List<Map<String, Object>> operations) {}
