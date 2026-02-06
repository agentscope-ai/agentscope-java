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
package io.agentscope.core.formatter.anthropic.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnthropicRequest}.
 *
 * <p>Tests cover defensive copying, null handling, and basic DTO functionality.
 */
class AnthropicRequestTest {

    @Test
    void testGetMetadataReturnsUnmodifiableView() {
        // Given: An AnthropicRequest with metadata
        AnthropicRequest request = new AnthropicRequest();
        Map<String, Object> originalMetadata = new HashMap<>();
        originalMetadata.put("user_id", "12345");
        originalMetadata.put("request_id", "abc-def");
        request.setMetadata(originalMetadata);

        // When: Getting the metadata
        Map<String, Object> returnedMetadata = request.getMetadata();

        // Then: The returned map should be unmodifiable
        assertNotNull(returnedMetadata, "Metadata should not be null");
        assertNotNull(returnedMetadata.getClass(), "Map class should not be null");

        // Test that it's actually unmodifiable by trying to remove an entry
        assertThrows(
                UnsupportedOperationException.class,
                () -> returnedMetadata.remove("user_id"),
                "Returned map should be unmodifiable - remove() should throw");

        // And: The original map should still be modifiable
        assertDoesNotThrow(() -> originalMetadata.put("another_key", "another_value"));

        // And: Changes to original map should be reflected in the returned view
        // (Collections.unmodifiableMap returns a view, not a copy)
        originalMetadata.put("modified_key", "modified_value");
        assertTrue(returnedMetadata.containsKey("modified_key"));
        assertEquals("modified_value", returnedMetadata.get("modified_key"));
    }

    @Test
    void testGetMetadataWhenNull() {
        // Given: An AnthropicRequest without metadata
        AnthropicRequest request = new AnthropicRequest();

        // When: Getting the metadata
        Map<String, Object> metadata = request.getMetadata();

        // Then: Should return null
        assertNull(metadata, "Metadata should be null when not set");
    }

    @Test
    void testSetAndGetMetadata() {
        // Given: An AnthropicRequest
        AnthropicRequest request = new AnthropicRequest();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 42);

        // When: Setting the metadata
        request.setMetadata(metadata);

        // Then: Should be retrievable and contain the same values
        Map<String, Object> retrieved = request.getMetadata();
        assertNotNull(retrieved);
        assertEquals(2, retrieved.size());
        assertEquals("value1", retrieved.get("key1"));
        assertEquals(42, retrieved.get("key2"));
    }

    @Test
    void testSetMetadataWithNull() {
        // Given: An AnthropicRequest with metadata
        AnthropicRequest request = new AnthropicRequest();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        request.setMetadata(metadata);

        // When: Setting metadata to null
        request.setMetadata(null);

        // Then: getMetadata should return null
        assertNull(request.getMetadata());
    }

    @Test
    void testMultipleGetMetadataCallsReturnDifferentUnmodifiableViews() {
        // Given: An AnthropicRequest with metadata
        AnthropicRequest request = new AnthropicRequest();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        request.setMetadata(metadata);

        // When: Getting metadata multiple times
        Map<String, Object> view1 = request.getMetadata();
        Map<String, Object> view2 = request.getMetadata();

        // Then: Both should be unmodifiable and represent the same underlying data
        assertNotNull(view1);
        assertNotNull(view2);
        assertEquals(view1.size(), view2.size());
        assertEquals(view1.get("key"), view2.get("key"));

        // Both should be unmodifiable - test remove() operation
        assertThrows(UnsupportedOperationException.class, () -> view1.remove("key"));
        assertThrows(UnsupportedOperationException.class, () -> view2.remove("key"));
    }

    @Test
    void testRequestWithAllFields() {
        // Given: A fully populated AnthropicRequest
        AnthropicRequest request = new AnthropicRequest();
        request.setModel("claude-sonnet-4-5-20250929");
        request.setMaxTokens(4096);
        request.setTemperature(0.7);
        request.setStream(true);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user_id", "test-user");
        request.setMetadata(metadata);

        // When: Getting all fields
        // Then: All should be correctly set
        assertEquals("claude-sonnet-4-5-20250929", request.getModel());
        assertEquals(4096, request.getMaxTokens());
        assertEquals(0.7, request.getTemperature());
        assertEquals(true, request.getStream());
        assertNotNull(request.getMetadata());
        assertEquals("test-user", request.getMetadata().get("user_id"));
    }

    @Test
    void testRequestDefaults() {
        // Given: A new AnthropicRequest with no fields set
        AnthropicRequest request = new AnthropicRequest();

        // When: Getting all fields
        // Then: All should be null or default values
        assertNull(request.getModel());
        assertNull(request.getMessages());
        assertNull(request.getMaxTokens());
        assertNull(request.getTemperature());
        assertNull(request.getTopP());
        assertNull(request.getTopK());
        assertNull(request.getSystem());
        assertNull(request.getTools());
        assertNull(request.getToolChoice());
        assertNull(request.getStream());
        assertNull(request.getStopSequences());
        assertNull(request.getMetadata());
    }

    @Test
    void testMetadataImmutabilityDoesNotAffectSetter() {
        // Given: An AnthropicRequest with initial metadata
        AnthropicRequest request = new AnthropicRequest();
        Map<String, Object> initialMetadata = new HashMap<>();
        initialMetadata.put("initial", "value");
        request.setMetadata(initialMetadata);

        // When: Getting unmodifiable view, then setting new metadata
        Map<String, Object> unmodifiableView = request.getMetadata();
        Map<String, Object> newMetadata = new HashMap<>();
        newMetadata.put("new", "data");
        request.setMetadata(newMetadata);

        // Then: The new metadata should be retrievable
        Map<String, Object> retrieved = request.getMetadata();
        assertNotNull(retrieved);
        assertEquals("data", retrieved.get("new"));
        assertFalse(retrieved.containsKey("initial"));
    }
}
