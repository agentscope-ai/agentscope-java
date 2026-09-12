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
package io.agentscope.extensions.model.openaiofficial;

import java.util.Set;

/**
 * Shared constants for the OpenAI official module.
 *
 * <p>Defines the provider id, the {@code additionalBodyParams} whitelist key set, and
 * metadata namespace keys ({@code openai.*}). All internal components reference these
 * constants so that metadata-key writes and history-replay reads use the exact same strings.
 */
final class OpenAIOfficialConstants {

    private OpenAIOfficialConstants() {}

    /** Provider id used in ModelException, SPI registration, and model id prefix. */
    static final String PROVIDER_ID = "openai-official";

    // ── additionalBodyParams whitelist ───────────────────────────

    /**
     * Whitelist of keys allowed in {@code GenerateOptions.additionalBodyParams}.
     * Non-whitelist keys trigger fail-fast during request mapping.
     */
    static final Set<String> ADDITIONAL_BODY_PARAMS_WHITELIST =
            Set.of(
                    "max_tool_calls",
                    "prompt_cache_key",
                    "prompt_cache_options",
                    "service_tier",
                    "safety_identifier",
                    "reasoning.summary",
                    "reasoning.context",
                    "reasoning.mode");

    // ── Metadata namespace keys ─────────────────────────

    // Response-level
    static final String MD_RESPONSE_ID = "openai.response.id";
    static final String MD_RESPONSE_STATUS = "openai.response.status";
    static final String MD_RESPONSE_CREATED_AT = "openai.response.created_at";
    static final String MD_RESPONSE_COMPLETED_AT = "openai.response.completed_at";
    static final String MD_RESPONSE_SERVICE_TIER = "openai.response.service_tier";
    static final String MD_RESPONSE_INCOMPLETE_REASON = "openai.response.incomplete_reason";
    static final String MD_RESPONSE_ERROR = "openai.response.error";

    // Usage-level
    static final String MD_USAGE_REASONING_TOKENS = "openai.usage.reasoning_tokens";

    // Reasoning-level (internal state, used for history replay)
    static final String MD_REASONING_ENCRYPTED_CONTENT = "openai.reasoning.encrypted_content";
    static final String MD_REASONING_TEXT = "openai.reasoning.text";
}
