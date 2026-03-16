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
package io.agentscope.core.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParseStage}.
 *
 * <p>Tests cover all enum values and their properties.
 *
 * @since 1.0.10
 */
@DisplayName("ParseStage Enum Tests")
class ParseStageTest {

    @Test
    @DisplayName("Should have all expected enum values")
    void shouldHaveAllExpectedEnumValues() {
        ParseStage[] stages = ParseStage.values();

        assertEquals(6, stages.length);
    }

    @Test
    @DisplayName("DIRECT stage should be first")
    void directStageShouldBeFirst() {
        assertEquals(ParseStage.DIRECT, ParseStage.values()[0]);
    }

    @Test
    @DisplayName("ORIGINAL stage should be last")
    void originalStageShouldBeLast() {
        ParseStage[] stages = ParseStage.values();
        assertEquals(ParseStage.ORIGINAL, stages[stages.length - 1]);
    }

    @Test
    @DisplayName("Should access enum constants")
    void shouldAccessEnumConstants() {
        assertNotNull(ParseStage.DIRECT);
        assertNotNull(ParseStage.MARKDOWN_CLEAN);
        assertNotNull(ParseStage.COMMENT_STRIP);
        assertNotNull(ParseStage.QUOTE_FIX);
        assertNotNull(ParseStage.JSON_REPAIR);
        assertNotNull(ParseStage.ORIGINAL);
    }

    @Test
    @DisplayName("Should access enum names")
    void shouldAccessEnumNames() {
        assertEquals("DIRECT", ParseStage.DIRECT.name());
        assertEquals("MARKDOWN_CLEAN", ParseStage.MARKDOWN_CLEAN.name());
        assertEquals("COMMENT_STRIP", ParseStage.COMMENT_STRIP.name());
        assertEquals("QUOTE_FIX", ParseStage.QUOTE_FIX.name());
        assertEquals("JSON_REPAIR", ParseStage.JSON_REPAIR.name());
        assertEquals("ORIGINAL", ParseStage.ORIGINAL.name());
    }

    @Test
    @DisplayName("Should access enum ordinals")
    void shouldAccessEnumOrdinals() {
        assertEquals(0, ParseStage.DIRECT.ordinal());
        assertEquals(1, ParseStage.MARKDOWN_CLEAN.ordinal());
        assertEquals(2, ParseStage.COMMENT_STRIP.ordinal());
        assertEquals(3, ParseStage.QUOTE_FIX.ordinal());
        assertEquals(4, ParseStage.JSON_REPAIR.ordinal());
        assertEquals(5, ParseStage.ORIGINAL.ordinal());
    }

    @Test
    @DisplayName("valueOf should return correct enum")
    void valueOfShouldReturnCorrectEnum() {
        assertEquals(ParseStage.DIRECT, ParseStage.valueOf("DIRECT"));
        assertEquals(ParseStage.MARKDOWN_CLEAN, ParseStage.valueOf("MARKDOWN_CLEAN"));
        assertEquals(ParseStage.COMMENT_STRIP, ParseStage.valueOf("COMMENT_STRIP"));
        assertEquals(ParseStage.QUOTE_FIX, ParseStage.valueOf("QUOTE_FIX"));
        assertEquals(ParseStage.JSON_REPAIR, ParseStage.valueOf("JSON_REPAIR"));
        assertEquals(ParseStage.ORIGINAL, ParseStage.valueOf("ORIGINAL"));
    }
}
