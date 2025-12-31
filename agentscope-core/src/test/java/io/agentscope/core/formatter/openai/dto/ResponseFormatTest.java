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
package io.agentscope.core.formatter.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ResponseFormat and JsonSchema DTOs.
 *
 * <p>Tests the structured output configuration for OpenAI API responses.
 */
@Tag("unit")
@DisplayName("ResponseFormat and JsonSchema Unit Tests")
class ResponseFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should create text response format")
    void testTextResponseFormat() {
        ResponseFormat format = ResponseFormat.text();

        assertNotNull(format);
        assertEquals("text", format.getType());
        assertNull(format.getJsonSchema());
    }

    @Test
    @DisplayName("Should create json_object response format")
    void testJsonObjectResponseFormat() {
        ResponseFormat format = ResponseFormat.jsonObject();

        assertNotNull(format);
        assertEquals("json_object", format.getType());
        assertNull(format.getJsonSchema());
    }

    @Test
    @DisplayName("Should create json_schema response format with schema")
    void testJsonSchemaResponseFormat() {
        JsonSchema schema = JsonSchema.builder().name("TestSchema").build();

        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        assertNotNull(format);
        assertEquals("json_schema", format.getType());
        assertNotNull(format.getJsonSchema());
        assertEquals("TestSchema", format.getJsonSchema().getName());
    }

    @Test
    @DisplayName("Should create ResponseFormat using builder with text type")
    void testResponseFormatBuilderText() {
        ResponseFormat format = ResponseFormat.builder().type("text").build();

        assertEquals("text", format.getType());
    }

    @Test
    @DisplayName("Should create ResponseFormat using builder with json_schema")
    void testResponseFormatBuilderJsonSchema() {
        JsonSchema schema = JsonSchema.builder().name("TestSchema").build();

        ResponseFormat format = ResponseFormat.builder().jsonSchema(schema).build();

        assertEquals("json_schema", format.getType());
        assertNotNull(format.getJsonSchema());
    }

    @Test
    @DisplayName("Should set and get type property")
    void testSetAndGetType() {
        ResponseFormat format = new ResponseFormat();
        format.setType("json_object");

        assertEquals("json_object", format.getType());
    }

    @Test
    @DisplayName("Should set and get jsonSchema property")
    void testSetAndGetJsonSchema() {
        ResponseFormat format = new ResponseFormat();
        JsonSchema schema = JsonSchema.builder().name("MySchema").build();

        format.setJsonSchema(schema);

        assertEquals(schema, format.getJsonSchema());
        assertEquals("MySchema", format.getJsonSchema().getName());
    }

    @Test
    @DisplayName("Should create JsonSchema with name")
    void testJsonSchemaWithName() {
        JsonSchema schema = JsonSchema.builder().name("MathResponse").build();

        assertEquals("MathResponse", schema.getName());
        assertNull(schema.getDescription());
        assertNull(schema.getSchema());
        assertNull(schema.getStrict());
    }

    @Test
    @DisplayName("Should create JsonSchema with description")
    void testJsonSchemaWithDescription() {
        JsonSchema schema =
                JsonSchema.builder()
                        .name("Response")
                        .description("Response with calculation steps")
                        .build();

        assertEquals("Response", schema.getName());
        assertEquals("Response with calculation steps", schema.getDescription());
    }

    @Test
    @DisplayName("Should create JsonSchema with schema definition")
    void testJsonSchemaWithSchemaDefinition() {
        Map<String, Object> schemaMap = new HashMap<>();
        schemaMap.put("type", "object");
        schemaMap.put("properties", Map.of("name", Map.of("type", "string")));

        JsonSchema schema = JsonSchema.builder().name("Person").schema(schemaMap).build();

        assertEquals("Person", schema.getName());
        assertNotNull(schema.getSchema());
        assertEquals("object", schema.getSchema().get("type"));
    }

    @Test
    @DisplayName("Should create JsonSchema with strict mode enabled")
    void testJsonSchemaWithStrictMode() {
        JsonSchema schema = JsonSchema.builder().name("StrictSchema").strict(true).build();

        assertEquals("StrictSchema", schema.getName());
        assertTrue(schema.getStrict());
    }

    @Test
    @DisplayName("Should create JsonSchema with strict mode disabled")
    void testJsonSchemaWithStrictModeDisabled() {
        JsonSchema schema = JsonSchema.builder().name("NonStrictSchema").strict(false).build();

        assertEquals("NonStrictSchema", schema.getName());
        assertFalse(schema.getStrict());
    }

    @Test
    @DisplayName("Should create complete JsonSchema with all properties")
    void testJsonSchemaComplete() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("answer", Map.of("type", "number"));
        properties.put("steps", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> schemaMap = new HashMap<>();
        schemaMap.put("type", "object");
        schemaMap.put("properties", properties);
        schemaMap.put("required", Arrays.asList("answer", "steps"));

        JsonSchema schema =
                JsonSchema.builder()
                        .name("MathResponse")
                        .description("Response with calculation steps")
                        .schema(schemaMap)
                        .strict(true)
                        .build();

        assertEquals("MathResponse", schema.getName());
        assertEquals("Response with calculation steps", schema.getDescription());
        assertNotNull(schema.getSchema());
        assertTrue(schema.getStrict());
        assertEquals("object", schema.getSchema().get("type"));
    }

    @Test
    @DisplayName("Should set and get JsonSchema properties")
    void testSetAndGetJsonSchemaProperties() {
        JsonSchema schema = new JsonSchema();

        schema.setName("TestSchema");
        schema.setDescription("Test description");
        schema.setStrict(true);

        Map<String, Object> schemaMap = Map.of("type", "object");
        schema.setSchema(schemaMap);

        assertEquals("TestSchema", schema.getName());
        assertEquals("Test description", schema.getDescription());
        assertTrue(schema.getStrict());
        assertEquals(schemaMap, schema.getSchema());
    }

    @Test
    @DisplayName("Should serialize ResponseFormat to JSON")
    void testSerializeResponseFormat() throws Exception {
        ResponseFormat format = ResponseFormat.jsonObject();

        String json = objectMapper.writeValueAsString(format);

        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"json_object\""));
    }

    @Test
    @DisplayName("Should serialize ResponseFormat with JsonSchema to JSON")
    void testSerializeResponseFormatWithJsonSchema() throws Exception {
        Map<String, Object> schemaMap =
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));

        JsonSchema schema = JsonSchema.builder().name("Person").schema(schemaMap).build();

        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        String json = objectMapper.writeValueAsString(format);

        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"json_schema\""));
        assertTrue(json.contains("\"name\":\"Person\""));
    }

    @Test
    @DisplayName("Should deserialize ResponseFormat from JSON")
    void testDeserializeResponseFormat() throws Exception {
        String json = "{\"type\":\"json_object\"}";

        ResponseFormat format = objectMapper.readValue(json, ResponseFormat.class);

        assertNotNull(format);
        assertEquals("json_object", format.getType());
        assertNull(format.getJsonSchema());
    }

    @Test
    @DisplayName("Should deserialize ResponseFormat with JsonSchema from JSON")
    void testDeserializeResponseFormatWithJsonSchema() throws Exception {
        String json =
                "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"Person\",\"schema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}}";

        ResponseFormat format = objectMapper.readValue(json, ResponseFormat.class);

        assertNotNull(format);
        assertEquals("json_schema", format.getType());
        assertNotNull(format.getJsonSchema());
        assertEquals("Person", format.getJsonSchema().getName());
    }

    @Test
    @DisplayName("Should exclude null fields in JSON serialization")
    void testExcludeNullFields() throws Exception {
        ResponseFormat format = new ResponseFormat();
        format.setType("text");

        String json = objectMapper.writeValueAsString(format);

        // JsonSchema should not be included if it's null
        assertFalse(json.contains("\"json_schema\":null"));
    }

    @Test
    @DisplayName("Should handle complex nested schema")
    void testComplexNestedSchema() {
        Map<String, Object> innerSchema = new HashMap<>();
        innerSchema.put("type", "object");
        innerSchema.put("properties", Map.of("value", Map.of("type", "string")));

        Map<String, Object> arraySchema = new HashMap<>();
        arraySchema.put("type", "array");
        arraySchema.put("items", innerSchema);

        Map<String, Object> mainSchema = new HashMap<>();
        mainSchema.put("type", "object");
        mainSchema.put("properties", Map.of("items", arraySchema));

        JsonSchema schema =
                JsonSchema.builder().name("ComplexSchema").schema(mainSchema).strict(true).build();

        assertEquals("ComplexSchema", schema.getName());
        assertNotNull(schema.getSchema());
        assertNotNull(schema.getSchema().get("properties"));
        assertTrue(schema.getStrict());
    }

    @Test
    @DisplayName("Should handle null JsonSchema in ResponseFormat")
    void testNullJsonSchemaInResponseFormat() throws Exception {
        ResponseFormat format = ResponseFormat.text();

        assertNull(format.getJsonSchema());

        String json = objectMapper.writeValueAsString(format);
        // Should not include json_schema field
        assertFalse(json.contains("json_schema"));
    }

    @Test
    @DisplayName("Should handle empty schema properties")
    void testEmptySchemaProperties() {
        JsonSchema schema =
                JsonSchema.builder().name("EmptySchema").schema(new HashMap<>()).build();

        assertNotNull(schema.getSchema());
        assertTrue(schema.getSchema().isEmpty());
    }

    @Test
    @DisplayName("Should allow builder chaining for ResponseFormat")
    void testResponseFormatBuilderChaining() {
        ResponseFormat format = ResponseFormat.builder().type("json_object").build();

        assertNotNull(format);
        assertEquals("json_object", format.getType());
    }

    @Test
    @DisplayName("Should allow builder chaining for JsonSchema")
    void testJsonSchemaBuilderChaining() {
        Map<String, Object> schema = Map.of("type", "object");

        JsonSchema jsonSchema =
                JsonSchema.builder()
                        .name("ChainedSchema")
                        .description("Testing chaining")
                        .schema(schema)
                        .strict(true)
                        .build();

        assertEquals("ChainedSchema", jsonSchema.getName());
        assertEquals("Testing chaining", jsonSchema.getDescription());
        assertTrue(jsonSchema.getStrict());
    }

    @Test
    @DisplayName("Should handle JsonSchema with null strict value")
    void testJsonSchemaWithNullStrict() {
        JsonSchema schema = JsonSchema.builder().name("NoStrictSchema").build();

        assertNull(schema.getStrict());
    }

    @Test
    @DisplayName("Should handle ResponseFormat with custom type via builder")
    void testResponseFormatBuilderWithCustomType() {
        ResponseFormat format = ResponseFormat.builder().type("custom_type").build();

        assertEquals("custom_type", format.getType());
    }
}
