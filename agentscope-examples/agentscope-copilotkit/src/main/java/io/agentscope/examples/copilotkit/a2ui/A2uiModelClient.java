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

import org.springframework.core.Ordered;

/**
 * One model backend able to turn an intent into A2UI JSON.
 *
 * <p>{@link A2uiComposer} walks the available clients in {@link Ordered} order, so Gemini is tried
 * before the DashScope fallback.
 */
public interface A2uiModelClient extends Ordered {

    /** Identifier recorded in the shared state as {@code a2ui.generatedBy}. */
    String name();

    /** Whether the required credentials are configured. */
    boolean isAvailable();

    /**
     * Generates raw model output; the composer extracts the {@code <a2ui-json>} block from it.
     *
     * @throws Exception when the model call fails, letting the composer move to the next client
     */
    String generate(String systemPrompt, String userPrompt) throws Exception;
}
