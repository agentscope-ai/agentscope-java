/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReconnectFailedException Tests")
class ReconnectFailedExceptionTest {

    @Test
    @DisplayName("Should create exception with message")
    void shouldCreateExceptionWithMessage() {
        ReconnectFailedException ex = new ReconnectFailedException("Reconnect failed");

        assertEquals("Reconnect failed", ex.getMessage());
        assertEquals(0, ex.getAttemptCount());
        assertEquals(0, ex.getMaxAttempts());
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        Exception cause = new RuntimeException("Network error");
        ReconnectFailedException ex = new ReconnectFailedException("Reconnect failed", cause);

        assertEquals("Reconnect failed", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertEquals(0, ex.getAttemptCount());
        assertEquals(0, ex.getMaxAttempts());
    }

    @Test
    @DisplayName("Should create exception with attempt info")
    void shouldCreateExceptionWithAttemptInfo() {
        ReconnectFailedException ex = new ReconnectFailedException("Max attempts reached", 5, 5);

        assertEquals(5, ex.getAttemptCount());
        assertEquals(5, ex.getMaxAttempts());
        assertTrue(ex.isMaxAttemptsReached());
    }

    @Test
    @DisplayName("Should create exception with attempt info and cause")
    void shouldCreateExceptionWithAttemptInfoAndCause() {
        Exception cause = new RuntimeException("Connection refused");
        ReconnectFailedException ex =
                new ReconnectFailedException("Max attempts reached", cause, 5, 5);

        assertEquals("Max attempts reached", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertEquals(5, ex.getAttemptCount());
        assertEquals(5, ex.getMaxAttempts());
        assertTrue(ex.isMaxAttemptsReached());
    }

    @Test
    @DisplayName("Should detect max attempts not reached")
    void shouldDetectMaxAttemptsNotReached() {
        ReconnectFailedException ex = new ReconnectFailedException("Connection error", 2, 5);

        assertFalse(ex.isMaxAttemptsReached());
    }

    @Test
    @DisplayName("Should handle zero max attempts")
    void shouldHandleZeroMaxAttempts() {
        ReconnectFailedException ex = new ReconnectFailedException("Error", 0, 0);

        assertFalse(ex.isMaxAttemptsReached());
    }
}
