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

package io.agentscope.core.mcp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestIdTest {

    @Test
    void testNumericRequestId() {
        RequestId id = RequestId.numeric(123L);
        assertTrue(id.isNumber());
        assertFalse(id.isString());
        assertEquals(123L, id.asLong());
    }

    @Test
    void testStringRequestId() {
        RequestId id = RequestId.string("request-1");
        assertTrue(id.isString());
        assertFalse(id.isNumber());
        assertEquals("request-1", id.asString());
    }

    @Test
    void testNumericRequestIdFromByte() {
        RequestId id = new RequestId((byte) 42);
        assertTrue(id.isNumber());
        assertEquals(42L, id.asLong());
    }

    @Test
    void testNumericRequestIdFromShort() {
        RequestId id = new RequestId((short) 1000);
        assertTrue(id.isNumber());
        assertEquals(1000L, id.asLong());
    }

    @Test
    void testNumericRequestIdFromInteger() {
        RequestId id = new RequestId(999);
        assertTrue(id.isNumber());
        assertEquals(999L, id.asLong());
    }

    @Test
    void testAsStringThrowsWhenNumeric() {
        RequestId id = RequestId.numeric(42L);
        assertThrows(IllegalStateException.class, id::asString);
    }

    @Test
    void testAsLongThrowsWhenString() {
        RequestId id = RequestId.string("not-numeric");
        assertThrows(IllegalStateException.class, id::asLong);
    }

    @Test
    void testNullValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(null));
    }

    @Test
    void testDoubleValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(3.14));
    }

    @Test
    void testFloatValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(2.71f));
    }

    @Test
    void testBooleanValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(true));
    }

    @Test
    void testObjectValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(new Object()));
    }

    @Test
    void testRecordEquality() {
        RequestId id1 = RequestId.numeric(42L);
        RequestId id2 = RequestId.numeric(42L);
        assertEquals(id1, id2);
    }

    @Test
    void testRecordInequalityDifferentValue() {
        RequestId id1 = RequestId.numeric(42L);
        RequestId id2 = RequestId.numeric(43L);
        assertNotEquals(id1, id2);
    }

    @Test
    void testRecordInequalityDifferentType() {
        RequestId id1 = RequestId.numeric(42L);
        RequestId id2 = RequestId.string("42");
        assertNotEquals(id1, id2);
    }
}
