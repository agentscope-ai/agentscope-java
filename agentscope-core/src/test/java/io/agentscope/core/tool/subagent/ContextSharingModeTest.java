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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for ContextSharingMode enum.
 */
@DisplayName("ContextSharingMode Tests")
class ContextSharingModeTest {

    @Test
    @DisplayName("fromString should return SHARED for 'shared' (case insensitive)")
    void testFromStringShared() {
        assertSame(ContextSharingMode.SHARED, ContextSharingMode.fromString("shared"));
        assertSame(ContextSharingMode.SHARED, ContextSharingMode.fromString("SHARED"));
        assertSame(ContextSharingMode.SHARED, ContextSharingMode.fromString("Shared"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "shared"})
    @DisplayName("fromString should return SHARED for null, empty, or 'shared'")
    void testFromStringSharedDefault(String value) {
        assertSame(ContextSharingMode.SHARED, ContextSharingMode.fromString(value));
    }

    @Test
    @DisplayName("fromString should return FORK for 'fork' (case insensitive)")
    void testFromStringFork() {
        assertSame(ContextSharingMode.FORK, ContextSharingMode.fromString("fork"));
        assertSame(ContextSharingMode.FORK, ContextSharingMode.fromString("FORK"));
        assertSame(ContextSharingMode.FORK, ContextSharingMode.fromString("Fork"));
    }

    @Test
    @DisplayName("fromString should return NEW for 'new' (case insensitive)")
    void testFromStringNew() {
        assertSame(ContextSharingMode.NEW, ContextSharingMode.fromString("new"));
        assertSame(ContextSharingMode.NEW, ContextSharingMode.fromString("NEW"));
        assertSame(ContextSharingMode.NEW, ContextSharingMode.fromString("New"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "invalid", "random", "SHARED_MODE"})
    @DisplayName("fromString should return SHARED (default) for unknown values")
    void testFromStringUnknownValues(String value) {
        assertSame(ContextSharingMode.SHARED, ContextSharingMode.fromString(value));
    }
}
