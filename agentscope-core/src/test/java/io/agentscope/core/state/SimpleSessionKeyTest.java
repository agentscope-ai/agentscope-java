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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for SimpleSessionKey. */
@DisplayName("SimpleSessionKey Tests")
class SimpleSessionKeyTest {

    @Test
    @DisplayName("Should create SimpleSessionKey with valid session ID")
    void testCreateWithValidSessionId() {
        SimpleSessionKey key = new SimpleSessionKey("user_123");
        assertEquals("user_123", key.sessionId());
    }

    @Test
    @DisplayName("Should create SimpleSessionKey via factory method")
    void testFactoryMethod() {
        SimpleSessionKey key = SimpleSessionKey.of("session_456");
        assertEquals("session_456", key.sessionId());
    }

    @Test
    @DisplayName("Should throw NullPointerException when session ID is null")
    void testNullSessionId() {
        assertThrows(NullPointerException.class, () -> new SimpleSessionKey(null));
    }

    @Test
    @DisplayName("Should throw NullPointerException when session ID is null via factory")
    void testNullSessionIdViaFactory() {
        assertThrows(NullPointerException.class, () -> SimpleSessionKey.of(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when session ID is blank")
    void testBlankSessionId() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleSessionKey(""));
        assertThrows(IllegalArgumentException.class, () -> new SimpleSessionKey("   "));
        assertThrows(IllegalArgumentException.class, () -> new SimpleSessionKey("\t\n"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when session ID is blank via factory")
    void testBlankSessionIdViaFactory() {
        assertThrows(IllegalArgumentException.class, () -> SimpleSessionKey.of(""));
        assertThrows(IllegalArgumentException.class, () -> SimpleSessionKey.of("   "));
    }

    @Test
    @DisplayName("Should return session ID as toString")
    void testToString() {
        SimpleSessionKey key = SimpleSessionKey.of("my_session");
        assertEquals("my_session", key.toString());
    }

    @Test
    @DisplayName("Should be equal when session IDs are equal")
    void testEquality() {
        SimpleSessionKey key1 = SimpleSessionKey.of("same_id");
        SimpleSessionKey key2 = SimpleSessionKey.of("same_id");
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when session IDs differ")
    void testInequality() {
        SimpleSessionKey key1 = SimpleSessionKey.of("id_1");
        SimpleSessionKey key2 = SimpleSessionKey.of("id_2");
        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("Should be usable as SessionKey type")
    void testUsableAsSessionKey() {
        SessionKey key = SimpleSessionKey.of("test");
        assertEquals("test", key.toString());
    }

    @Test
    @DisplayName("Should accept various valid session ID formats")
    void testValidFormats() {
        // Test various formats that should be valid
        assertEquals("user123", SimpleSessionKey.of("user123").sessionId());
        assertEquals("user-123", SimpleSessionKey.of("user-123").sessionId());
        assertEquals("user_123", SimpleSessionKey.of("user_123").sessionId());
        assertEquals("USER_123", SimpleSessionKey.of("USER_123").sessionId());
        assertEquals("123", SimpleSessionKey.of("123").sessionId());
        assertEquals("a", SimpleSessionKey.of("a").sessionId());
        assertEquals("user/session/123", SimpleSessionKey.of("user/session/123").sessionId());
    }
}
