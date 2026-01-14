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
package io.agentscope.core.formatter.dashscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DashScopeResponseFormat}
 *
 * <p>Tests getter, setter and builder methods
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
class DashScopeResponseFormatTest {

    @Test
    void testCustomBuilder() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(User.class);
        DashScopeResponseFormat responseFormat =
                DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(
                                DashScopeResponseFormat.JsonSchemaConfig.builder()
                                        .name("user_info")
                                        .description("The user information")
                                        .strict(true)
                                        .schema(schema)
                                        .build())
                        .build();

        assertEquals(DashScopeResponseFormat.Type.JSON_SCHEMA, responseFormat.getType());
        assertEquals("user_info", responseFormat.getJsonScheme().getName());
        assertEquals("The user information", responseFormat.getJsonScheme().getDescription());
        assertEquals(schema, responseFormat.getJsonScheme().getSchema());
        assertTrue(responseFormat.getJsonScheme().getStrict());
    }

    @Test
    void testDefaultBuilder() {
        DashScopeResponseFormat responseFormat = DashScopeResponseFormat.builder().build();

        assertEquals(DashScopeResponseFormat.Type.TEXT, responseFormat.getType());
        assertNull(responseFormat.getJsonScheme());
    }

    @Test
    void testGetterAndSetter() {
        DashScopeResponseFormat responseFormat = DashScopeResponseFormat.builder().build();
        responseFormat.setType(DashScopeResponseFormat.Type.JSON_SCHEMA);
        DashScopeResponseFormat.JsonSchemaConfig jsonSchema =
                DashScopeResponseFormat.JsonSchemaConfig.builder().build();
        responseFormat.setJsonScheme(jsonSchema);
        jsonSchema.setName("user_info");
        jsonSchema.setDescription("The user information");
        jsonSchema.setStrict(true);
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(User.class);
        jsonSchema.setSchema(schema);

        assertEquals(DashScopeResponseFormat.Type.JSON_SCHEMA, responseFormat.getType());
        assertNotNull(responseFormat.getJsonScheme());
        assertEquals("user_info", responseFormat.getJsonScheme().getName());
        assertEquals("The user information", responseFormat.getJsonScheme().getDescription());
        assertEquals(true, responseFormat.getJsonScheme().getStrict());
        assertEquals(schema, responseFormat.getJsonScheme().getSchema());
    }

    @Test
    void testJsonGeneration() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(User.class);
        DashScopeResponseFormat responseFormat =
                DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(
                                DashScopeResponseFormat.JsonSchemaConfig.builder()
                                        .name("user_info")
                                        .description("The user information")
                                        .strict(true)
                                        .schema(schema)
                                        .build())
                        .build();
        String json = JsonUtils.getJsonCodec().toJson(responseFormat);
        assertTrue(json.contains("\"type\":\"json_schema\""));
        assertTrue(json.contains("\"json_schema\":{"));
        assertTrue(json.contains("\"name\":\"user_info\""));
        assertTrue(json.contains("\"description\":\"The user information\""));
        assertTrue(json.contains("\"schema\":{"));
        assertTrue(json.contains("\"strict\":true"));
    }

    @Test
    void testJsonGenerationIncludeNonNull() {
        DashScopeResponseFormat textResponseFormat = DashScopeResponseFormat.builder().build();
        DashScopeResponseFormat jsonObjectResponseFormat =
                DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_OBJECT)
                        .build();
        String textResponseFormatJson = JsonUtils.getJsonCodec().toJson(textResponseFormat);
        String jsonObjectResponseFormatJson =
                JsonUtils.getJsonCodec().toJson(jsonObjectResponseFormat);

        assertEquals(
                """
                {"type":"text"}\
                """,
                textResponseFormatJson);

        assertEquals(
                """
                {"type":"json_object"}\
                """,
                jsonObjectResponseFormatJson);
    }

    private record User(
            @JsonPropertyDescription("The user name") @JsonProperty(value = "name", required = true)
                    String name,
            @JsonPropertyDescription("The user age") @JsonProperty(value = "age", required = true)
                    int age,
            @JsonPropertyDescription("The user email address") @JsonProperty("email")
                    String email) {}
}
