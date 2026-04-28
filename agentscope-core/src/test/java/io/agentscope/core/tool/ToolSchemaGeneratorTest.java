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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
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
}
