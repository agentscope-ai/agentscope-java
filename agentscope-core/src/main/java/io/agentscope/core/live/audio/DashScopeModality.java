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
package io.agentscope.core.live.audio;

import java.util.List;

/**
 * DashScope output modality configuration.
 *
 * <p>Defines the output modalities for DashScope Realtime API sessions. DashScope supports two
 * combinations:
 *
 * <ul>
 *   <li>{@link #TEXT} - Text output only (["text"])
 *   <li>{@link #TEXT_AND_AUDIO} - Both text and audio output (["text", "audio"]) - default
 * </ul>
 *
 * <p>Note: DashScope does not support audio-only output (["audio"] is not valid).
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * DashScopeLiveModel model = DashScopeLiveModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("qwen-omni-turbo-realtime")
 *     .modality(DashScopeModality.TEXT)  // Text-only output
 *     .webSocketClient(JdkWebSocketClient.create())
 *     .build();
 * }</pre>
 */
public enum DashScopeModality {

    /** Text output only. Maps to ["text"] in the API. */
    TEXT,

    /** Both text and audio output. Maps to ["text", "audio"] in the API. This is the default. */
    TEXT_AND_AUDIO;

    /**
     * Converts this modality to the API list format.
     *
     * @return list of modality strings for the DashScope API
     */
    public List<String> toApiList() {
        return this == TEXT ? List.of("text") : List.of("text", "audio");
    }
}
