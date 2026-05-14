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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("ToolSchemaGenerator Tests")
class ToolSchemaGeneratorTest {

    private final ToolSchemaGenerator generator = new ToolSchemaGenerator();

    @Test
    @DisplayName("Should throw when hoisted defs contain conflicting definition for same key")
    void testHoistDefsConflict() throws Exception {
        Method hoistDefsMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "hoistDefs", Map.class, String.class, Map.class);
        hoistDefsMethod.setAccessible(true);

        Map<String, Object> existingDef =
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")));
        Map<String, Object> conflictDef =
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer")));

        Map<String, Object> target = new HashMap<>();
        target.put("Material", existingDef);
        Map<String, Object> paramSchema = new HashMap<>();
        paramSchema.put("$defs", Map.of("Material", conflictDef));

        InvocationTargetException exception =
                assertThrows(
                        InvocationTargetException.class,
                        () -> hoistDefsMethod.invoke(generator, paramSchema, "$defs", target));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("Material"));
    }

    @Test
    @DisplayName("Should allow hoisted defs when same key has equivalent definition")
    void testHoistDefsEquivalent() throws Exception {
        Method hoistDefsMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "hoistDefs", Map.class, String.class, Map.class);
        hoistDefsMethod.setAccessible(true);

        Map<String, Object> definition =
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")));

        Map<String, Object> target = new HashMap<>();
        target.put("Material", definition);
        Map<String, Object> paramSchema = new HashMap<>();
        paramSchema.put("$defs", Map.of("Material", definition));

        assertDoesNotThrow(() -> hoistDefsMethod.invoke(generator, paramSchema, "$defs", target));
        assertEquals(1, target.size());
        assertEquals(definition, target.get("Material"));
        assertFalse(paramSchema.containsKey("$defs"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should inject additionalProperties=false recursively into nested structures")
    void testAddAdditionalPropertiesFalseRecursively() throws Exception {
        Method recMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "addAdditionalPropertiesFalseRecursively", Map.class);
        recMethod.setAccessible(true);

        // Object with items
        Map<String, Object> schemaWithItems = new HashMap<>();
        schemaWithItems.put("type", "object");
        Map<String, Object> itemsObj = new HashMap<>();
        itemsObj.put("type", "object");
        schemaWithItems.put("items", itemsObj);

        recMethod.invoke(generator, schemaWithItems);

        assertEquals(false, schemaWithItems.get("additionalProperties"));
        assertEquals(false, itemsObj.get("additionalProperties"));

        // Object with oneOf
        Map<String, Object> schemaWithOneOf = new HashMap<>();
        schemaWithOneOf.put("type", "object");
        Map<String, Object> oneOfItem = new HashMap<>();
        oneOfItem.put("type", "object");
        schemaWithOneOf.put("oneOf", List.of(oneOfItem));

        recMethod.invoke(generator, schemaWithOneOf);

        assertEquals(false, schemaWithOneOf.get("additionalProperties"));
        assertEquals(false, oneOfItem.get("additionalProperties"));

        // Object with anyOf and allOf
        Map<String, Object> schemaWithAnyOf = new HashMap<>();
        schemaWithAnyOf.put("type", "object");
        Map<String, Object> anyOfItem = new HashMap<>();
        anyOfItem.put("type", "object");
        Map<String, Object> allOfItem = new HashMap<>();
        allOfItem.put("type", "object");
        schemaWithAnyOf.put("anyOf", List.of(anyOfItem));
        schemaWithAnyOf.put("allOf", List.of(allOfItem));

        recMethod.invoke(generator, schemaWithAnyOf);

        assertEquals(false, anyOfItem.get("additionalProperties"));
        assertEquals(false, allOfItem.get("additionalProperties"));

        // Object with $defs
        Map<String, Object> schemaWithDefs = new HashMap<>();
        schemaWithDefs.put("type", "object");
        Map<String, Object> defEntry = new HashMap<>();
        defEntry.put("type", "object");
        schemaWithDefs.put("$defs", Map.of("MyType", defEntry));

        recMethod.invoke(generator, schemaWithDefs);

        assertEquals(false, defEntry.get("additionalProperties"));

        // Object with definitions (legacy key)
        Map<String, Object> schemaWithDefinitions = new HashMap<>();
        schemaWithDefinitions.put("type", "object");
        Map<String, Object> defEntry2 = new HashMap<>();
        defEntry2.put("type", "object");
        schemaWithDefinitions.put("definitions", Map.of("LegacyType", defEntry2));

        recMethod.invoke(generator, schemaWithDefinitions);

        assertEquals(false, defEntry2.get("additionalProperties"));
    }

    @Test
    @DisplayName("Should skip when additionalProperties already set")
    void testSkipWhenAdditionalPropertiesAlreadySet() throws Exception {
        Method recMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "addAdditionalPropertiesFalseRecursively", Map.class);
        recMethod.setAccessible(true);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);

        recMethod.invoke(generator, schema);

        assertEquals(true, schema.get("additionalProperties"));
    }

    @Test
    @DisplayName("Should skip non-object schemas")
    void testSkipNonObjectSchemas() throws Exception {
        Method recMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "addAdditionalPropertiesFalseRecursively", Map.class);
        recMethod.setAccessible(true);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "string");

        recMethod.invoke(generator, schema);

        assertNull(schema.get("additionalProperties"));
    }

    @Test
    @DisplayName("Should handle properties with non-Map values")
    void testPropertiesWithNonMapValues() throws Exception {
        Method recMethod =
                ToolSchemaGenerator.class.getDeclaredMethod(
                        "addAdditionalPropertiesFalseRecursively", Map.class);
        recMethod.setAccessible(true);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("simple", "not-a-map"));

        recMethod.invoke(generator, schema);

        assertEquals(false, schema.get("additionalProperties"));
    }
}
