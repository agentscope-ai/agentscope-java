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
import io.agentscope.core.message.ContentBlockMetadataKeys;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utilities for persisting and restoring Gemini thought signatures. */
final class GeminiThoughtSignatureUtils {

    private static final Logger log = LoggerFactory.getLogger(GeminiThoughtSignatureUtils.class);

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

    /**
     * Restore a persisted signature onto a Gemini Part builder.
     *
     * <p>A signature that is missing or corrupted after a persistence round trip is skipped with a
     * warning instead of failing the whole model call, matching the tolerant behavior of the
     * response parser: a dropped signature degrades to a request without it rather than aborting
     * the conversation replay. Callers that attach protocol flags depending on the signature (such
     * as the {@code thought} flag) can use the return value to degrade the Part themselves.
     *
     * @return true if a signature was restored onto the Part builder, false otherwise
     */
    static boolean applyMetadata(Part.Builder partBuilder, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }

        Object value = metadata.get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);
        if (value == null) {
            return false;
        }

        if (value instanceof byte[] signature) {
            if (signature.length == 0) {
                log.warn("Skipping empty Gemini thought signature");
                return false;
            }
            partBuilder.thoughtSignature(signature.clone());
            return true;
        }

        if (!(value instanceof String encodedSignature)) {
            log.warn(
                    "Skipping Gemini thought signature with unsupported metadata type: {}",
                    value.getClass().getName());
            return false;
        }

        if (encodedSignature.isEmpty()) {
            log.warn("Skipping empty Gemini thought signature");
            return false;
        }

        try {
            partBuilder.thoughtSignature(Base64.getDecoder().decode(encodedSignature));
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Skipping non-Base64 Gemini thought signature: {}", e.getMessage());
            return false;
        }
    }
}
