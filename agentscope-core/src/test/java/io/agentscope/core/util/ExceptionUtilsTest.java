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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExceptionUtilsTest {

    @Test
    void testGetErrorMessageWithNullThrowable() {
        assertEquals("Unknown error", ExceptionUtils.getErrorMessage(null));
    }

    @Test
    void testGetErrorMessageReturnsOwnMessage() {
        assertEquals("boom", ExceptionUtils.getErrorMessage(new IllegalStateException("boom")));
    }

    @Test
    void testGetErrorMessageKeepsWhitespaceMessage() {
        assertEquals(" ", ExceptionUtils.getErrorMessage(new RuntimeException(" ")));
    }

    @Test
    void testGetErrorMessageFallsBackToCauseMessageWhenEmpty() {
        RuntimeException error = new RuntimeException("", new IllegalStateException("root cause"));
        assertEquals("root cause", ExceptionUtils.getErrorMessage(error));
    }

    @Test
    void testGetErrorMessageFallsBackToCauseMessageWhenNull() {
        RuntimeException error =
                new RuntimeException(null, new IllegalStateException("root cause"));
        assertEquals("root cause", ExceptionUtils.getErrorMessage(error));
    }

    @Test
    void testGetErrorMessageFallsBackToClassNameWithoutCause() {
        assertEquals(
                "IllegalStateException",
                ExceptionUtils.getErrorMessage(new IllegalStateException()));
    }

    @Test
    void testGetErrorMessageFallsBackToClassNameWhenCauseMessageEmpty() {
        RuntimeException error = new RuntimeException("", new IllegalStateException(""));
        assertEquals("RuntimeException", ExceptionUtils.getErrorMessage(error));
    }

    @Test
    void testGetErrorMessageFallsBackToClassNameWhenCauseMessageNull() {
        RuntimeException error = new RuntimeException(null, new IllegalStateException());
        assertEquals("RuntimeException", ExceptionUtils.getErrorMessage(error));
    }

    @Test
    void testGetErrorMessageDoesNotSearchBeyondDirectCause() {
        RuntimeException error =
                new RuntimeException(
                        null,
                        new IllegalStateException(null, new IllegalArgumentException("deep")));
        assertEquals("RuntimeException", ExceptionUtils.getErrorMessage(error));
    }

    @Test
    void testContainsInterruptedExceptionWithNull() {
        assertFalse(ExceptionUtils.containsInterruptedException(null));
    }

    @Test
    void testContainsInterruptedExceptionDirect() {
        assertTrue(ExceptionUtils.containsInterruptedException(new InterruptedException()));
    }

    @Test
    void testContainsInterruptedExceptionNested() {
        RuntimeException error = new RuntimeException("wrap", new InterruptedException());
        assertTrue(ExceptionUtils.containsInterruptedException(error));
    }

    @Test
    void testContainsInterruptedExceptionDeepChain() {
        RuntimeException error =
                new RuntimeException(
                        "l1",
                        new IllegalStateException(
                                "l2", new RuntimeException("l3", new InterruptedException())));
        assertTrue(ExceptionUtils.containsInterruptedException(error));
    }

    @Test
    void testContainsInterruptedExceptionAbsent() {
        RuntimeException error = new RuntimeException("a", new IllegalStateException("b"));
        assertFalse(ExceptionUtils.containsInterruptedException(error));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void testContainsInterruptedExceptionTerminatesOnCircularCause() {
        RuntimeException first = new RuntimeException("first");
        IllegalStateException second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);
        assertFalse(ExceptionUtils.containsInterruptedException(first));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void testContainsInterruptedExceptionFoundInsideCycle() {
        RuntimeException outer = new RuntimeException("outer");
        InterruptedException interrupt = new InterruptedException("interrupted");
        outer.initCause(interrupt);
        interrupt.initCause(outer);
        assertTrue(ExceptionUtils.containsInterruptedException(outer));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void testContainsInterruptedExceptionTerminatesOnSelfReturningCause() {
        RuntimeException selfCaused =
                new RuntimeException("self") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };
        assertFalse(ExceptionUtils.containsInterruptedException(selfCaused));
    }
}
