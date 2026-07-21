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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.genai.types.Part;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlockMetadataKeys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiThoughtSignatureUtilsTest {

    @Test
    void shouldReturnNullMetadataWhenPartHasNoSignature() {
        assertNull(
                GeminiThoughtSignatureUtils.extractMetadata(Part.builder().text("text").build()));
    }

    @Test
    void shouldExtractDefensiveSignatureCopy() {
        byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);
        Part part = Part.builder().thoughtSignature(signature).build();

        Map<String, Object> metadata = GeminiThoughtSignatureUtils.extractMetadata(part);
        byte[] extracted = (byte[]) metadata.get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);

        assertArrayEquals(signature, extracted);
        assertNotSame(part.thoughtSignature().orElseThrow(), extracted);
    }

    @Test
    void shouldIgnoreAbsentSignatureMetadata() {
        Part.Builder nullMetadataBuilder = Part.builder().text("text");
        Part.Builder emptyMetadataBuilder = Part.builder().text("text");
        Part.Builder unrelatedMetadataBuilder = Part.builder().text("text");
        Part.Builder nullValueBuilder = Part.builder().text("text");
        Map<String, Object> nullValueMetadata = new HashMap<>();
        nullValueMetadata.put(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, null);

        GeminiThoughtSignatureUtils.applyMetadata(nullMetadataBuilder, null);
        GeminiThoughtSignatureUtils.applyMetadata(emptyMetadataBuilder, Map.of());
        GeminiThoughtSignatureUtils.applyMetadata(
                unrelatedMetadataBuilder, Map.of("provider", "gemini"));
        GeminiThoughtSignatureUtils.applyMetadata(nullValueBuilder, nullValueMetadata);

        assertFalse(nullMetadataBuilder.build().thoughtSignature().isPresent());
        assertFalse(emptyMetadataBuilder.build().thoughtSignature().isPresent());
        assertFalse(unrelatedMetadataBuilder.build().thoughtSignature().isPresent());
        assertFalse(nullValueBuilder.build().thoughtSignature().isPresent());
    }

    @Test
    void shouldApplyDefensiveByteArrayCopy() {
        byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);
        byte[] expected = signature.clone();
        Part.Builder partBuilder = Part.builder().text("text");

        GeminiThoughtSignatureUtils.applyMetadata(
                partBuilder, Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature));
        signature[0] = (byte) (signature[0] + 1);

        assertArrayEquals(expected, partBuilder.build().thoughtSignature().orElseThrow());
    }

    @Test
    void shouldDecodeBase64Signature() {
        byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(signature);
        Part.Builder partBuilder = Part.builder().text("text");

        GeminiThoughtSignatureUtils.applyMetadata(
                partBuilder, Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, encoded));

        assertArrayEquals(signature, partBuilder.build().thoughtSignature().orElseThrow());
    }

    @Test
    void shouldRejectUnsupportedSignatureType() {
        Part.Builder partBuilder = Part.builder().text("text");

        FormatterException exception =
                assertThrows(
                        FormatterException.class,
                        () ->
                                GeminiThoughtSignatureUtils.applyMetadata(
                                        partBuilder,
                                        Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, 123)));

        assertEquals(
                "Unsupported Gemini thought signature metadata type: java.lang.Integer",
                exception.getMessage());
    }

    @Test
    void shouldRejectEmptySignature() {
        Part.Builder partBuilder = Part.builder().text("text");

        FormatterException exception =
                assertThrows(
                        FormatterException.class,
                        () ->
                                GeminiThoughtSignatureUtils.applyMetadata(
                                        partBuilder,
                                        Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, "")));

        assertEquals("Gemini thought signature must not be empty", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidBase64Signature() {
        Part.Builder partBuilder = Part.builder().text("text");

        FormatterException exception =
                assertThrows(
                        FormatterException.class,
                        () ->
                                GeminiThoughtSignatureUtils.applyMetadata(
                                        partBuilder,
                                        Map.of(
                                                ContentBlockMetadataKeys.THOUGHT_SIGNATURE,
                                                "not-valid-base64!")));

        assertEquals("Invalid Base64 Gemini thought signature", exception.getMessage());
    }
}
