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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TypeUtilsTest {

    static class TestClass {
        private String value;

        TestClass(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    static class AnotherClass {
        private int number;

        AnotherClass(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    @Test
    void testSafeCastWithValidCast() {
        TestClass testObj = new TestClass("test");
        TestClass result = TypeUtils.safeCast(testObj, TestClass.class);

        assertEquals(testObj, result);
        assertEquals("test", result.getValue());
    }

    @Test
    void testSafeCastWithNullObject() {
        TestClass result = TypeUtils.safeCast(null, TestClass.class);
        assertNull(result);
    }

    @Test
    void testSafeCastWithInvalidCast() {
        AnotherClass anotherObj = new AnotherClass(42);

        ClassCastException exception =
                assertThrows(
                        ClassCastException.class,
                        () -> TypeUtils.safeCast(anotherObj, TestClass.class));

        assertTrue(exception.getMessage().contains("Cannot cast"));
        assertTrue(exception.getMessage().contains("AnotherClass"));
        assertTrue(exception.getMessage().contains("TestClass"));
    }

    @Test
    void testSafeCastWithInheritance() {
        String str = "test string";
        Object obj = str;

        String result = TypeUtils.safeCast(obj, String.class);
        assertEquals(str, result);

        CharSequence charSeqResult = TypeUtils.safeCast(obj, CharSequence.class);
        assertEquals(str, charSeqResult);
    }

    @Test
    void testSafeCastOptionalWithValidCast() {
        TestClass testObj = new TestClass("test");
        Optional<TestClass> result = TypeUtils.safeCastOptional(testObj, TestClass.class);

        assertTrue(result.isPresent());
        assertEquals(testObj, result.get());
        assertEquals("test", result.get().getValue());
    }

    @Test
    void testSafeCastOptionalWithNullObject() {
        Optional<TestClass> result = TypeUtils.safeCastOptional(null, TestClass.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void testSafeCastOptionalWithInvalidCast() {
        AnotherClass anotherObj = new AnotherClass(42);
        Optional<TestClass> result = TypeUtils.safeCastOptional(anotherObj, TestClass.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void testSafeCastOptionalWithInheritance() {
        String str = "test string";
        Object obj = str;

        Optional<String> stringResult = TypeUtils.safeCastOptional(obj, String.class);
        assertTrue(stringResult.isPresent());
        assertEquals(str, stringResult.get());

        Optional<CharSequence> charSeqResult = TypeUtils.safeCastOptional(obj, CharSequence.class);
        assertTrue(charSeqResult.isPresent());
        assertEquals(str, charSeqResult.get());
    }
}
