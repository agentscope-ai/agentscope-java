/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DefaultToolResultConverter.
 * Tests serialization of various types including Java 8 date/time types.
 */
class DefaultToolResultConverterTest {

    private DefaultToolResultConverter converter;
    private DefaultToolResultConverter converterWithCustomMapper;

    @BeforeEach
    void setUp() {
        // Test both constructors
        converter = new DefaultToolResultConverter();
        converterWithCustomMapper = new DefaultToolResultConverter();
    }

    @Test
    void testConvert_NullResult() {
        ToolResultBlock result = converter.convert(null, String.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("null", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_VoidType() {
        ToolResultBlock result = converter.convert("ignored", Void.TYPE);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("Done", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_ToolResultBlockPassThrough() {
        ToolResultBlock original =
                ToolResultBlock.of(List.of(TextBlock.builder().text("test result").build()));

        ToolResultBlock result = converter.convert(original, ToolResultBlock.class);

        assertNotNull(result);
        assertEquals(original, result);
        assertEquals("test result", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_StringResult() {
        String testString = "Hello, World!";

        ToolResultBlock result = converter.convert(testString, String.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("\"Hello, World!\"", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_IntegerResult() {
        Integer testInteger = 42;

        ToolResultBlock result = converter.convert(testInteger, Integer.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("42", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_BooleanResult() {
        Boolean testBoolean = true;

        ToolResultBlock result = converter.convert(testBoolean, Boolean.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("true", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_ListResult() {
        List<String> testList = Arrays.asList("item1", "item2", "item3");

        ToolResultBlock result = converter.convert(testList, List.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("item1"));
        assertTrue(content.contains("item2"));
        assertTrue(content.contains("item3"));
    }

    @Test
    void testConvert_MapResult() {
        Map<String, Object> testMap = new HashMap<>();
        testMap.put("key1", "value1");
        testMap.put("key2", 123);
        testMap.put("key3", true);

        ToolResultBlock result = converter.convert(testMap, Map.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("key1"));
        assertTrue(content.contains("value1"));
        assertTrue(content.contains("key2"));
        assertTrue(content.contains("123"));
    }

    @Test
    void testConvert_ComplexObject() {
        TestPojo pojo = new TestPojo("TestName", 100, true);

        ToolResultBlock result = converter.convert(pojo, TestPojo.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("TestName"));
        assertTrue(content.contains("100"));
        assertTrue(content.contains("true"));
    }

    @Test
    void testConvert_LocalDateWithDefaultConstructor() {
        LocalDate testDate = LocalDate.of(2025, 12, 31);

        // This should work because default constructor now uses
        // JsonSchemaUtils.getJsonScheamObjectMapper()
        ToolResultBlock result = converter.convert(testDate, LocalDate.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        // LocalDate serializes as array by default: [2025,12,31]
        assertTrue(content.contains("2025"));
        assertTrue(content.contains("12"));
        assertTrue(content.contains("31"));
    }

    @Test
    void testConvert_LocalDateTimeWithDefaultConstructor() {
        LocalDateTime testDateTime = LocalDateTime.of(2025, 12, 31, 23, 59, 59);

        ToolResultBlock result = converter.convert(testDateTime, LocalDateTime.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("2025"));
        assertTrue(content.contains("12"));
        assertTrue(content.contains("31"));
    }

    @Test
    void testConvert_OffsetDateTimeWithDefaultConstructor() {
        OffsetDateTime testDateTime =
                OffsetDateTime.of(2025, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(8));

        ToolResultBlock result = converter.convert(testDateTime, OffsetDateTime.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        // OffsetDateTime serializes as decimal timestamp: 1767225599.000000000
        assertNotNull(content);
        assertFalse(content.isEmpty());
        // Verify it's a number (timestamp format)
        assertTrue(content.matches("\\d+\\.\\d+"));
    }

    @Test
    void testConvert_ComplexObjectWithLocalDate() {
        TestPojoWithDate pojo =
                new TestPojoWithDate("Event", LocalDate.of(2025, 1, 15), "Description");

        ToolResultBlock result = converter.convert(pojo, TestPojoWithDate.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("Event"));
        assertTrue(content.contains("2025"));
        assertTrue(content.contains("Description"));
    }

    @Test
    void testConvert_WithCustomMapper_UsesProvidedMapper() {
        String testString = "Custom Mapper Test";

        ToolResultBlock result = converterWithCustomMapper.convert(testString, String.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        assertEquals("\"Custom Mapper Test\"", ToolTestUtils.extractContent(result));
    }

    @Test
    void testConvert_UnserializableObject_FallbackToString() {
        // Create an object that cannot be serialized easily
        UnserializableObject unserializable = new UnserializableObject();

        ToolResultBlock result = converterWithCustomMapper.convert(unserializable, Object.class);

        // Should fallback to toString() representation
        assertNotNull(result);
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("UnserializableObject"));
    }

    @Test
    void testDefaultConstructor_UsesJsonSchemaUtilsMapper() {
        // Verify that the default constructor uses the ObjectMapper from JsonSchemaUtils
        DefaultToolResultConverter defaultConverter = new DefaultToolResultConverter();

        LocalDate testDate = LocalDate.of(2025, 6, 15);
        ToolResultBlock result = defaultConverter.convert(testDate, LocalDate.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        // Should successfully serialize without throwing InvalidDefinitionException
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("2025"));
    }

    @Test
    void testParameterizedConstructor_AcceptsCustomMapper() {
        DefaultToolResultConverter customConverter = new DefaultToolResultConverter();

        LocalDate testDate = LocalDate.of(2025, 3, 20);
        ToolResultBlock result = customConverter.convert(testDate, LocalDate.class);

        assertNotNull(result);
        assertFalse(ToolTestUtils.isErrorResponse(result));
        String content = ToolTestUtils.extractContent(result);
        assertTrue(content.contains("2025"));
    }

    // Test POJOs
    static class TestPojo {
        private String name;
        private int value;
        private boolean active;

        public TestPojo(String name, int value, boolean active) {
            this.name = name;
            this.value = value;
            this.active = active;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }

        public boolean isActive() {
            return active;
        }
    }

    static class TestPojoWithDate {
        private String title;
        private LocalDate date;
        private String description;

        public TestPojoWithDate(String title, LocalDate date, String description) {
            this.title = title;
            this.date = date;
            this.description = description;
        }

        public String getTitle() {
            return title;
        }

        public LocalDate getDate() {
            return date;
        }

        public String getDescription() {
            return description;
        }
    }

    static class UnserializableObject {
        // Object with circular reference
        private UnserializableObject self = this;

        @Override
        public String toString() {
            return "UnserializableObject@" + Integer.toHexString(hashCode());
        }
    }
}
