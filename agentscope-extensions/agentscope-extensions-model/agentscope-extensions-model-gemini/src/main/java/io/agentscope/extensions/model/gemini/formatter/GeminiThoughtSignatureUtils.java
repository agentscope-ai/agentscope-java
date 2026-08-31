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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.Part;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlockMetadataKeys;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Utilities for persisting and restoring Gemini thought signatures. */
final class GeminiThoughtSignatureUtils {

    private GeminiThoughtSignatureUtils() {}

    /** Extract a Part signature into content block metadata. */
    static Map<String, Object> extractMetadata(Part part) {
        byte[] signature = part.thoughtSignature().orElse(null);
        if (signature == null) {
            return null;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature.clone());
        return metadata;
    }

    /** Restore a persisted signature onto a Gemini Part builder. */
    static void applyMetadata(Part.Builder partBuilder, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        Object value = metadata.get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);
        if (value == null) {
            return;
        }

        if (value instanceof byte[] signature) {
            partBuilder.thoughtSignature(signature.clone());
            return;
        }

        if (!(value instanceof String encodedSignature)) {
            throw new FormatterException(
                    "Unsupported Gemini thought signature metadata type: "
                            + value.getClass().getName());
        }

        if (encodedSignature.isEmpty()) {
            throw new FormatterException("Gemini thought signature must not be empty");
        }

        try {
            partBuilder.thoughtSignature(Base64.getDecoder().decode(encodedSignature));
        } catch (IllegalArgumentException e) {
            throw new FormatterException("Invalid Base64 Gemini thought signature", e);
        }
    }
}
