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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkingBlockTest {

    @Test
    void testBuilderWithThinkingText() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("reasoning").build();

        assertEquals("reasoning", block.getThinking());
        assertNull(block.getMetadata());
        assertNull(block.getSignature());
    }

    @Test
    void testBuilderWithNullThinkingDefaultsToEmptyString() {
        ThinkingBlock block = ThinkingBlock.builder().thinking(null).build();

        assertEquals("", block.getThinking());
    }

    @Test
    void testSignatureConvenienceMethodSetsMetadata() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("t").signature("sig-123").build();

        assertNotNull(block.getMetadata());
        assertEquals("sig-123", block.getSignature());
        assertEquals("sig-123", block.getMetadata().get(ThinkingBlock.METADATA_THOUGHT_SIGNATURE));
    }

    @Test
    void testSignatureNullDoesNotCreateMetadata() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("t").signature(null).build();

        assertNull(block.getMetadata());
        assertNull(block.getSignature());
    }

    @Test
    void testMetadataIsDefensivelyCopiedFromBuilderInput() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ThinkingBlock.METADATA_THOUGHT_SIGNATURE, "sig-a");
        metadata.put("k", "v");

        ThinkingBlock block = ThinkingBlock.builder().thinking("t").metadata(metadata).build();

        // mutate source map after build, block metadata should stay unchanged
        metadata.put(ThinkingBlock.METADATA_THOUGHT_SIGNATURE, "sig-b");
        metadata.put("k", "changed");

        assertEquals("sig-a", block.getSignature());
        assertEquals("v", block.getMetadata().get("k"));
    }

    @Test
    void testGetSignatureReturnsNullForNonStringMetadataValue() {
        ThinkingBlock block =
                ThinkingBlock.builder()
                        .thinking("t")
                        .metadata(Map.of(ThinkingBlock.METADATA_THOUGHT_SIGNATURE, 123))
                        .build();

        assertNull(block.getSignature());
    }

    @Test
    void testReasoningDetailsMetadataKeyCanBeStored() {
        ThinkingBlock block =
                ThinkingBlock.builder()
                        .thinking("t")
                        .metadata(
                                Map.of(
                                        ThinkingBlock.METADATA_REASONING_DETAILS,
                                        "[{\"type\":\"text\"}]"))
                        .build();

        assertTrue(block.getMetadata().containsKey(ThinkingBlock.METADATA_REASONING_DETAILS));
    }

    @Test
    void testJsonCreatorHandlesNullThinkingAsEmptyString() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ThinkingBlock block =
                mapper.readValue(
                        "{\"type\":\"thinking\",\"thinking\":null,\"metadata\":{\"k\":\"v\"}}",
                        ThinkingBlock.class);

        assertEquals("", block.getThinking());
        assertNotNull(block.getMetadata());
        assertEquals("v", block.getMetadata().get("k"));
    }

    @Test
    void testSignatureConvenienceMethodUsesExistingMetadataMap() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("existing", "value");

        ThinkingBlock block =
                ThinkingBlock.builder()
                        .thinking("t")
                        .metadata(metadata)
                        .signature("sig-existing")
                        .build();

        assertEquals("value", block.getMetadata().get("existing"));
        assertEquals("sig-existing", block.getSignature());
    }
}
