/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.live.formatter;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.Base64;
import java.util.List;

/**
 * Abstract base class for text-based (JSON) protocol formatters.
 *
 * <p>Applicable to DashScope, OpenAI, and Gemini. Provides common JSON serialization and audio
 * processing logic. Works directly with String - no byte[] conversion needed.
 */
public abstract class AbstractTextLiveFormatter implements LiveFormatter<String> {

    /**
     * Format Msg to JSON string (implemented by subclasses).
     *
     * @param msg the input message
     * @return JSON string representation, or null if unsupported
     */
    protected String formatInputToJson(Msg msg) {
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            ContentBlock block = msg.getContent().get(0);

            if (block instanceof ControlBlock controlBlock) {
                return formatControl(controlBlock);
            }

            if (block instanceof AudioBlock audioBlock) {
                byte[] audioData = extractAudioData(audioBlock);
                if (audioData != null) {
                    return formatAudio(audioData);
                }
            }

            return formatOtherContent(msg, block);
        }
        return null;
    }

    @Override
    public String formatInput(Msg msg) {
        if (msg == null) {
            return null;
        }
        return formatInputToJson(msg);
    }

    /**
     * Parse LiveEvent from JSON string (implemented by subclasses).
     *
     * @param json the JSON string to parse
     * @return parsed LiveEvent
     */
    protected abstract LiveEvent parseOutputFromJson(String json);

    @Override
    public LiveEvent parseOutput(String data) {
        return parseOutputFromJson(data);
    }

    /**
     * Build session configuration JSON (implemented by subclasses).
     *
     * @param config session configuration
     * @param toolSchemas tool definition list
     * @return JSON string for session configuration
     */
    protected abstract String buildSessionConfigJson(
            LiveConfig config, List<ToolSchema> toolSchemas);

    @Override
    public String buildSessionConfig(LiveConfig config, List<ToolSchema> toolSchemas) {
        return buildSessionConfigJson(config, toolSchemas);
    }

    /**
     * Format control signal to JSON string.
     *
     * @param controlBlock the control block to format
     * @return JSON string representation
     */
    protected abstract String formatControl(ControlBlock controlBlock);

    /**
     * Format audio data to JSON string.
     *
     * @param audioData raw audio bytes
     * @return JSON string representation
     */
    protected abstract String formatAudio(byte[] audioData);

    /**
     * Format other content types (implemented by subclasses).
     *
     * @param msg the original message
     * @param block the content block to format
     * @return JSON string representation, or null if not supported
     */
    protected String formatOtherContent(Msg msg, ContentBlock block) {
        return null; // Default: not supported
    }

    /**
     * Extract audio data from AudioBlock.
     *
     * @param audioBlock the audio block
     * @return raw audio bytes, or null if not a RawSource
     */
    protected byte[] extractAudioData(AudioBlock audioBlock) {
        if (audioBlock.getSource() instanceof RawSource rawSource) {
            return rawSource.getDataUnsafe();
        }
        return null;
    }

    /**
     * Base64 encode audio data.
     *
     * @param data raw bytes to encode
     * @return Base64 encoded string
     */
    protected String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * Base64 decode audio data.
     *
     * @param data Base64 encoded string
     * @return decoded raw bytes
     */
    protected byte[] decodeBase64(String data) {
        return Base64.getDecoder().decode(data);
    }

    /**
     * Serialize object to JSON.
     *
     * @param obj object to serialize
     * @return JSON string
     */
    protected String toJson(Object obj) {
        return JsonUtils.getJsonCodec().toJson(obj);
    }

    /**
     * Parse object from JSON.
     *
     * @param json JSON string to parse
     * @param clazz target class
     * @param <T> target type
     * @return parsed object
     */
    protected <T> T fromJson(String json, Class<T> clazz) {
        return JsonUtils.getJsonCodec().fromJson(json, clazz);
    }
}
