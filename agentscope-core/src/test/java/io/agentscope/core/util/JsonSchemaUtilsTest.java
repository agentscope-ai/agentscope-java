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
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaUtilsTest {

    static class SimpleModel {
        public String name;
        public int age;
    }

    static class NestedModel {
        public String title;
        public SimpleModel author;
        public List<String> tags;
    }

    @Test
    void testGenerateSchemaFromClassSimple() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(SimpleModel.class);

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("age"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nameProperty = (Map<String, Object>) properties.get("name");
        assertEquals("string", nameProperty.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ageProperty = (Map<String, Object>) properties.get("age");
        assertEquals("integer", ageProperty.get("type"));
    }

    @Test
    void testGenerateSchemaFromClassNested() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(NestedModel.class);

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);

        assertTrue(properties.containsKey("author"));
        assertTrue(properties.containsKey("tags"));

        @SuppressWarnings("unchecked")
        Map<String, Object> authorProperty = (Map<String, Object>) properties.get("author");
        assertNotNull(authorProperty);

        @SuppressWarnings("unchecked")
        Map<String, Object> tagsProperty = (Map<String, Object>) properties.get("tags");
        assertNotNull(tagsProperty);
        assertEquals("array", tagsProperty.get("type"));
    }

    @Test
    void testConvertToObjectSimple() {
        Map<String, Object> data = Map.of("name", "Alice", "age", 30);

        SimpleModel result = JsonSchemaUtils.convertToObject(data, SimpleModel.class);

        assertNotNull(result);
        assertEquals("Alice", result.name);
        assertEquals(30, result.age);
    }

    @Test
    void testConvertToObjectNested() {
        Map<String, Object> authorData = Map.of("name", "Bob", "age", 25);
        Map<String, Object> data =
                Map.of(
                        "title",
                        "Test Article",
                        "author",
                        authorData,
                        "tags",
                        List.of("java", "test"));

        NestedModel result = JsonSchemaUtils.convertToObject(data, NestedModel.class);

        assertNotNull(result);
        assertEquals("Test Article", result.title);
        assertNotNull(result.author);
        assertEquals("Bob", result.author.name);
        assertEquals(25, result.author.age);
        assertNotNull(result.tags);
        assertEquals(2, result.tags.size());
        assertTrue(result.tags.contains("java"));
        assertTrue(result.tags.contains("test"));
    }

    @Test
    void testConvertToObjectNull() {
        assertThrows(
                IllegalStateException.class,
                () -> JsonSchemaUtils.convertToObject(null, SimpleModel.class));
    }

    @Test
    void testConvertToObjectInvalidData() {
        Map<String, Object> invalidData = Map.of("name", "Alice", "age", "not-a-number");

        assertThrows(
                RuntimeException.class,
                () -> JsonSchemaUtils.convertToObject(invalidData, SimpleModel.class));
    }

    @Test
    void testGenerateSchemaFromType() {
        // Test List<String>
        Type listType = new TypeReference<List<String>>() {}.getType();
        Map<String, Object> listSchema = JsonSchemaUtils.generateSchemaFromType(listType);
        assertNotNull(listSchema);
        assertEquals("array", listSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) listSchema.get("items");
        assertNotNull(items);
        assertEquals("string", items.get("type"));

        // Test Map<String, Integer>
        Type mapType = new TypeReference<Map<String, Integer>>() {}.getType();
        Map<String, Object> mapSchema = JsonSchemaUtils.generateSchemaFromType(mapType);
        assertNotNull(mapSchema);
        assertEquals("object", mapSchema.get("type"));
    }
}
