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

package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JacksonJsonCodecTest {

    private JacksonJsonCodec codec;

    static class SimpleModel {
        public String name;
        public int age;

        public SimpleModel() {}

        public SimpleModel(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    static class NestedModel {
        public String title;
        public SimpleModel author;
        public List<String> tags;

        public NestedModel() {}

        public NestedModel(String title, SimpleModel author, List<String> tags) {
            this.title = title;
            this.author = author;
            this.tags = tags;
        }
    }

    static class TimeModel {
        public LocalDateTime timestamp;

        public TimeModel() {}

        public TimeModel(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
    }

    @BeforeEach
    void setUp() {
        codec = new JacksonJsonCodec();
    }

    @Test
    void testDefaultConstructor() {
        JacksonJsonCodec defaultCodec = new JacksonJsonCodec();
        assertNotNull(defaultCodec.getObjectMapper());
    }

    @Test
    void testCustomObjectMapperConstructor() {
        ObjectMapper customMapper = new ObjectMapper();
        JacksonJsonCodec customCodec = new JacksonJsonCodec(customMapper);
        assertEquals(customMapper, customCodec.getObjectMapper());
    }

    @Test
    void testToJsonSimple() {
        SimpleModel model = new SimpleModel("Alice", 30);
        String json = codec.toJson(model);

        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"age\":30"));
    }

    @Test
    void testToJsonNested() {
        SimpleModel author = new SimpleModel("Bob", 25);
        NestedModel model = new NestedModel("Test Article", author, List.of("java", "test"));
        String json = codec.toJson(model);

        assertNotNull(json);
        assertTrue(json.contains("\"title\":\"Test Article\""));
        assertTrue(json.contains("\"name\":\"Bob\""));
        assertTrue(json.contains("\"tags\""));
    }

    @Test
    void testToJsonNull() {
        String json = codec.toJson(null);
        assertEquals("null", json);
    }

    @Test
    void testToPrettyJson() {
        SimpleModel model = new SimpleModel("Alice", 30);
        String prettyJson = codec.toPrettyJson(model);

        assertNotNull(prettyJson);
        assertTrue(prettyJson.contains("\n"));
        assertTrue(prettyJson.contains("\"name\" : \"Alice\""));
    }

    @Test
    void testFromJsonSimple() {
        String json = "{\"name\":\"Alice\",\"age\":30}";
        SimpleModel model = codec.fromJson(json, SimpleModel.class);

        assertNotNull(model);
        assertEquals("Alice", model.name);
        assertEquals(30, model.age);
    }

    @Test
    void testFromJsonNested() {
        String json =
                "{\"title\":\"Test"
                    + " Article\",\"author\":{\"name\":\"Bob\",\"age\":25},\"tags\":[\"java\",\"test\"]}";
        NestedModel model = codec.fromJson(json, NestedModel.class);

        assertNotNull(model);
        assertEquals("Test Article", model.title);
        assertNotNull(model.author);
        assertEquals("Bob", model.author.name);
        assertEquals(25, model.author.age);
        assertNotNull(model.tags);
        assertEquals(2, model.tags.size());
        assertTrue(model.tags.contains("java"));
        assertTrue(model.tags.contains("test"));
    }

    @Test
    void testFromJsonWithUnknownProperties() {
        String json = "{\"name\":\"Alice\",\"age\":30,\"unknownField\":\"value\"}";
        SimpleModel model = codec.fromJson(json, SimpleModel.class);

        assertNotNull(model);
        assertEquals("Alice", model.name);
        assertEquals(30, model.age);
    }

    @Test
    void testFromJsonInvalidJson() {
        String invalidJson = "{invalid json}";
        assertThrows(JsonException.class, () -> codec.fromJson(invalidJson, SimpleModel.class));
    }

    @Test
    void testFromJsonWithTypeReference() {
        String json = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";
        List<SimpleModel> models = codec.fromJson(json, new TypeReference<List<SimpleModel>>() {});

        assertNotNull(models);
        assertEquals(2, models.size());
        assertEquals("Alice", models.get(0).name);
        assertEquals("Bob", models.get(1).name);
    }

    @Test
    void testFromJsonWithTypeReferenceMap() {
        String json = "{\"key1\":\"value1\",\"key2\":\"value2\"}";
        Map<String, String> map = codec.fromJson(json, new TypeReference<Map<String, String>>() {});

        assertNotNull(map);
        assertEquals(2, map.size());
        assertEquals("value1", map.get("key1"));
        assertEquals("value2", map.get("key2"));
    }

    @Test
    void testFromJsonWithTypeReferenceInvalidJson() {
        String invalidJson = "{invalid json}";
        assertThrows(
                JsonException.class,
                () -> codec.fromJson(invalidJson, new TypeReference<List<SimpleModel>>() {}));
    }

    @Test
    void testConvertValueMapToObject() {
        Map<String, Object> data = Map.of("name", "Alice", "age", 30);
        SimpleModel model = codec.convertValue(data, SimpleModel.class);

        assertNotNull(model);
        assertEquals("Alice", model.name);
        assertEquals(30, model.age);
    }

    @Test
    void testConvertValueObjectToMap() {
        SimpleModel model = new SimpleModel("Alice", 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = codec.convertValue(model, Map.class);

        assertNotNull(map);
        assertEquals("Alice", map.get("name"));
        assertEquals(30, map.get("age"));
    }

    @Test
    void testConvertValueWithTypeReference() {
        Map<String, Object> data = Map.of("name", "Alice", "age", 30);
        Map<String, Object> result =
                codec.convertValue(data, new TypeReference<Map<String, Object>>() {});

        assertNotNull(result);
        assertEquals("Alice", result.get("name"));
        assertEquals(30, result.get("age"));
    }

    @Test
    void testConvertValueInvalidConversion() {
        String invalidData = "not a valid object for conversion";
        assertThrows(JsonException.class, () -> codec.convertValue(invalidData, SimpleModel.class));
    }

    @Test
    void testJavaTimeModuleSupport() {
        LocalDateTime now = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        TimeModel model = new TimeModel(now);

        String json = codec.toJson(model);
        assertNotNull(json);

        TimeModel deserialized = codec.fromJson(json, TimeModel.class);
        assertNotNull(deserialized);
        assertNotNull(deserialized.timestamp);
    }

    @Test
    void testRoundTripSerialization() {
        SimpleModel original = new SimpleModel("Alice", 30);
        String json = codec.toJson(original);
        SimpleModel deserialized = codec.fromJson(json, SimpleModel.class);

        assertEquals(original.name, deserialized.name);
        assertEquals(original.age, deserialized.age);
    }

    @Test
    void testRoundTripSerializationNested() {
        SimpleModel author = new SimpleModel("Bob", 25);
        NestedModel original = new NestedModel("Test Article", author, List.of("java", "test"));

        String json = codec.toJson(original);
        NestedModel deserialized = codec.fromJson(json, NestedModel.class);

        assertEquals(original.title, deserialized.title);
        assertEquals(original.author.name, deserialized.author.name);
        assertEquals(original.author.age, deserialized.author.age);
        assertEquals(original.tags, deserialized.tags);
    }
}
