/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
        
 * limitations under the License.
 */
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ToolSchemaGeneratorTest {

    static class TestFunctions {
        public void exampleFunc(@ToolParam(name = "tags") String[] tags) {}
    }

    @Test
    void testArraySchemaHasItems() throws NoSuchMethodException {
        ToolSchemaGenerator generator = new ToolSchemaGenerator();
        Method method = TestFunctions.class.getMethod("exampleFunc", String[].class);

        Map<String, Object> schema = generator.generateParameterSchema(method);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        // Check array parameter
        Map<String, Object> tagsSchema = (Map<String, Object>) properties.get("tags");
        assertEquals("array", tagsSchema.get("type"));
        assertNotNull(tagsSchema.get("items"), "Array schema should have 'items' property");

        Map<String, Object> items = (Map<String, Object>) tagsSchema.get("items");
        assertEquals("string", items.get("type"));
    }
}
