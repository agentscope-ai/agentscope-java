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
package io.agentscope.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExceptionUtils}.
 */
@Tag("unit")
class ExceptionUtilsTest {

    @Test
    @DisplayName("Should extract error message from exception")
    void testGetErrorMessage() {
        JsonException exception = new JsonException("invalid json");
        assertEquals("invalid json", ExceptionUtils.getErrorMessage(exception));
    }

    @Test
    @DisplayName("Should return Unknown error for null")
    void testGetErrorMessageNull() {
        assertEquals("Unknown error", ExceptionUtils.getErrorMessage(null));
    }

    @Test
    @DisplayName("Should return simple class name when message is empty")
    void testGetErrorMessageEmptyMessage() {
        assertEquals(
                "IllegalArgumentException",
                ExceptionUtils.getErrorMessage(new IllegalArgumentException()));
        assertEquals(
                "IllegalArgumentException",
                ExceptionUtils.getErrorMessage(new IllegalArgumentException("")));
    }

    @Test
    @DisplayName("Should return root cause for nested exception")
    void testNestedGetRootCause() {
        RuntimeException runtimeException =
                new RuntimeException(
                        new JsonException(new IllegalArgumentException("invalid json")));
        Throwable rootCause = ExceptionUtils.getRootCause(runtimeException);
        assertSame(rootCause, runtimeException.getCause().getCause());
        assertInstanceOf(IllegalArgumentException.class, rootCause);
    }

    @Test
    @DisplayName("Should return null for single exception")
    void testSingleGetRootCause() {
        RuntimeException runtimeException = new RuntimeException("invalid json");
        Throwable rootCause = ExceptionUtils.getRootCause(runtimeException);
        assertSame(rootCause, runtimeException.getCause());
        assertNull(rootCause);
    }

    @Test
    @DisplayName("Should return expected type root cause for nested exception")
    void testNestedGetRootCauseExpectedType() {
        RuntimeException runtimeException =
                new RuntimeException(
                        new JsonException(new IllegalArgumentException("invalid json")));
        Throwable rootCause = ExceptionUtils.getRootCause(runtimeException, JsonException.class);
        assertSame(rootCause, runtimeException.getCause());
        assertInstanceOf(JsonException.class, rootCause);
    }

    @Test
    @DisplayName("Should return null if do not have expected type exception")
    void testNestedGetRootCauseNotHaveExpectedType() {
        RuntimeException runtimeException =
                new RuntimeException(
                        new JsonException(new IllegalArgumentException("invalid json")));
        Throwable rootCause =
                ExceptionUtils.getRootCause(runtimeException, NullPointerException.class);
        assertNull(rootCause);
    }

    @Test
    @DisplayName("Should return null for null input")
    void testGetRootCauseNull() {
        assertNull(ExceptionUtils.getRootCause(null));
        assertNull(ExceptionUtils.getRootCause(null, null));
    }
}
