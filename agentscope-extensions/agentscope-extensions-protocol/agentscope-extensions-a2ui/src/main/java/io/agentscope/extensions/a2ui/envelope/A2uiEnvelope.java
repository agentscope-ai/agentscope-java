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
package io.agentscope.extensions.a2ui.envelope;

import java.util.List;
import java.util.Map;

/**
 * Single-layer A2UI envelope; the serialized form <em>is</em> the tool result text that the
 * frontend parses out of {@code TOOL_CALL_RESULT.content}.
 *
 * @param protocolVersion always {@link A2uiConstants#PROTOCOL_VERSION}
 * @param messageType {@code createSurface} or {@code updateComponents}
 * @param surfaceId system-generated, stable within a session
 * @param catalogId component catalog this envelope was validated against
 * @param components flat list of {@code {id, component, props}} objects
 */
public record A2uiEnvelope(
        String protocolVersion,
        String messageType,
        String surfaceId,
        String catalogId,
        List<Map<String, Object>> components) {}
