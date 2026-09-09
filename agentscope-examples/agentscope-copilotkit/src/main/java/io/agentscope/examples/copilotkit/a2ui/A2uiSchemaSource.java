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
import java.util.Set;

/**
 * Supplies the A2UI grammar shown to the model and turns the reply back into a checked payload.
 *
 * <p>The default implementation is {@link BundledA2uiSchemaSource}: a hand-written grammar plus the
 * structural checks in {@link A2uiOperations}.
 *
 * <p>Prompting and parsing are deliberately one contract rather than two: a grammar is only
 * meaningful next to the validator that enforces it.
 */
public interface A2uiSchemaSource {

    /** Short identifier surfaced to the browser so the demo can show which path is active. */
    String name();

    /**
     * Builds the system prompt for the A2UI composer.
     *
     * @param clientComponents the custom components the browser declared it can render; empty means
     *     no restriction, so the full server-side catalog is advertised
     */
    String systemPrompt(Set<String> clientComponents);

    /**
     * Parses and validates a raw model reply.
     *
     * @param rawModelReply the model's full answer, expected to carry a {@code <a2ui-json>} block
     * @param surfaceId the id the caller allocated; operations are rewritten onto it so a model that
     *     invents its own id still produces a surface the browser can address
     * @param clientComponents components the browser can render, used to scope validation
     * @return the A2UI server-to-client messages, ready to be put on the wire
     * @throws RuntimeException when the reply is unusable, which makes the composer fall through to
     *     the next model client and finally to the deterministic fallback
     */
    List<Map<String, Object>> parse(
            String rawModelReply, String surfaceId, Set<String> clientComponents);
}
