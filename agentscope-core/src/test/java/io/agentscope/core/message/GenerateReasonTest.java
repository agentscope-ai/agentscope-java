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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GenerateReason enum and Msg integration.
 */
@Tag("unit")
@DisplayName("GenerateReason Unit Tests")
class GenerateReasonTest {

    @Test
    @DisplayName("Should have all expected enum values")
    void testEnumValues() {
        GenerateReason[] values = GenerateReason.values();
        assertEquals(8, values.length);

        // Verify all expected values exist
        assertNotNull(GenerateReason.MODEL_STOP);
        assertNotNull(GenerateReason.TOOL_CALLS);
        assertNotNull(GenerateReason.STRUCTURED_OUTPUT);
        assertNotNull(GenerateReason.TOOL_SUSPENDED);
        assertNotNull(GenerateReason.REASONING_STOP_REQUESTED);
        assertNotNull(GenerateReason.ACTING_STOP_REQUESTED);
        assertNotNull(GenerateReason.INTERRUPTED);
        assertNotNull(GenerateReason.MAX_ITERATIONS);
    }

    @Test
    @DisplayName("Should get default GenerateReason from Msg without metadata")
    void testDefaultGenerateReason() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hello").build();

        assertEquals(GenerateReason.MODEL_STOP, msg.getGenerateReason());
    }

    @Test
    @DisplayName("Should get GenerateReason from Msg with generateReason set via builder")
    void testGenerateReasonFromBuilder() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("External tool needed")
                        .generateReason(GenerateReason.TOOL_SUSPENDED)
                        .build();

        assertEquals(GenerateReason.TOOL_SUSPENDED, msg.getGenerateReason());
    }

    @Test
    @DisplayName("Should get GenerateReason from Msg via withGenerateReason")
    void testWithGenerateReason() {
        Msg original = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hello").build();

        Msg updated = original.withGenerateReason(GenerateReason.MAX_ITERATIONS);

        // Original should be unchanged
        assertEquals(GenerateReason.MODEL_STOP, original.getGenerateReason());
        // Updated should have new reason
        assertEquals(GenerateReason.MAX_ITERATIONS, updated.getGenerateReason());
    }

    @Test
    @DisplayName("Should handle GenerateReason stored as String in metadata")
    void testGenerateReasonFromStringMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_GENERATE_REASON, "INTERRUPTED");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("Interrupted")
                        .metadata(metadata)
                        .build();

        assertEquals(GenerateReason.INTERRUPTED, msg.getGenerateReason());
    }

    @Test
    @DisplayName("Should return MODEL_STOP for invalid GenerateReason string")
    void testInvalidGenerateReasonString() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_GENERATE_REASON, "INVALID_REASON");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("Test")
                        .metadata(metadata)
                        .build();

        assertEquals(GenerateReason.MODEL_STOP, msg.getGenerateReason());
    }

    @Test
    @DisplayName("Should preserve other metadata when setting GenerateReason")
    void testPreserveMetadataWithGenerateReason() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("custom_key", "custom_value");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("Test")
                        .metadata(metadata)
                        .generateReason(GenerateReason.TOOL_SUSPENDED)
                        .build();

        assertEquals(GenerateReason.TOOL_SUSPENDED, msg.getGenerateReason());
        assertEquals("custom_value", msg.getMetadata().get("custom_key"));
    }

    @Test
    @DisplayName("Should work with all GenerateReason values via builder")
    void testAllGenerateReasonValues() {
        for (GenerateReason reason : GenerateReason.values()) {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .textContent("Test")
                            .generateReason(reason)
                            .build();

            assertEquals(reason, msg.getGenerateReason());
        }
    }

    @Test
    @DisplayName("Should work with all GenerateReason values via withGenerateReason")
    void testAllGenerateReasonValuesViaWith() {
        Msg original = Msg.builder().role(MsgRole.ASSISTANT).textContent("Test").build();

        for (GenerateReason reason : GenerateReason.values()) {
            Msg updated = original.withGenerateReason(reason);
            assertEquals(reason, updated.getGenerateReason());
        }
    }
}
