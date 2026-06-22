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
package io.agentscope.core.memory.mem0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Mem0Message}. */
class Mem0MessageTest {

    @Test
    void testDefaultConstructor() {
        Mem0Message message = new Mem0Message();
        assertNotNull(message);
        assertNull(message.getRole());
        assertNull(message.getContent());
        assertNull(message.getName());
    }

    @Test
    void testTwoParamConstructor() {
        Mem0Message message = new Mem0Message("user", "Hello, world!");

        assertEquals("user", message.getRole());
        assertEquals("Hello, world!", message.getContent());
        assertNull(message.getName());
    }

    @Test
    void testThreeParamConstructor() {
        Mem0Message message = new Mem0Message("assistant", "Hi there!", "Agent1");

        assertEquals("assistant", message.getRole());
        assertEquals("Hi there!", message.getContent());
        assertEquals("Agent1", message.getName());
    }

    @Test
    void testBuilder() {
        Mem0Message message =
                Mem0Message.builder().role("user").content("Test message").name("TestUser").build();

        assertEquals("user", message.getRole());
        assertEquals("Test message", message.getContent());
        assertEquals("TestUser", message.getName());
    }

    @Test
    void testBuilderWithoutName() {
        Mem0Message message = Mem0Message.builder().role("assistant").content("Response").build();

        assertEquals("assistant", message.getRole());
        assertEquals("Response", message.getContent());
        assertNull(message.getName());
    }

    @Test
    void testSetters() {
        Mem0Message message = new Mem0Message();
        message.setRole("user");
        message.setContent("Updated content");
        message.setName("UpdatedName");

        assertEquals("user", message.getRole());
        assertEquals("Updated content", message.getContent());
        assertEquals("UpdatedName", message.getName());
    }

    @Test
    void testJsonSerialization() throws Exception {
        Mem0Message message =
                Mem0Message.builder()
                        .role("user")
                        .content("Test serialization")
                        .name("TestUser")
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(message);
        assertNotNull(json);

        // Verify all fields are present in JSON
        assert json.contains("\"role\"");
        assert json.contains("\"content\"");
        assert json.contains("\"name\"");
        assert json.contains("user");
        assert json.contains("Test serialization");
        assert json.contains("TestUser");
    }

    @Test
    void testJsonDeserialization() throws Exception {
        String json = "{\"role\":\"assistant\",\"content\":\"Hello\",\"name\":\"Agent\"}";

        Mem0Message message = JsonUtils.getJsonCodec().fromJson(json, Mem0Message.class);

        assertNotNull(message);
        assertEquals("assistant", message.getRole());
        assertEquals("Hello", message.getContent());
        assertEquals("Agent", message.getName());
    }

    @Test
    void testJsonDeserializationWithoutName() throws Exception {
        String json = "{\"role\":\"user\",\"content\":\"Test\"}";

        Mem0Message message = JsonUtils.getJsonCodec().fromJson(json, Mem0Message.class);

        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("Test", message.getContent());
        assertNull(message.getName());
    }

    @Test
    void testJsonRoundTrip() throws Exception {
        Mem0Message original =
                Mem0Message.builder()
                        .role("user")
                        .content("Round trip test")
                        .name("RoundTripUser")
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(original);
        Mem0Message deserialized = JsonUtils.getJsonCodec().fromJson(json, Mem0Message.class);

        assertEquals(original.getRole(), deserialized.getRole());
        assertEquals(original.getContent(), deserialized.getContent());
        assertEquals(original.getName(), deserialized.getName());
    }

    @Test
    void testJsonExcludesNullFields() throws Exception {
        Mem0Message message = Mem0Message.builder().role("user").content("Test").build();

        String json = JsonUtils.getJsonCodec().toJson(message);

        // The @JsonInclude(Include.NON_NULL) should exclude null name field
        assert !json.contains("\"name\"");
    }
}
