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
