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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InMemoryAguiResumeStateStoreTest {

    private static final Map<String, AguiEvent.Interrupt> PENDING =
            Map.of(
                    "I",
                    new AguiEvent.Interrupt("I", "tool_call", "approve", "tool", null, null, null));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void invalidThreadIdIsRejectedWithoutChangingState(String threadId) {
        InMemoryAguiResumeStateStore store = seededStore();
        List<Executable> calls =
                List.of(
                        () -> store.getPendingInterrupts(threadId),
                        () -> store.claimRun(threadId, "owner"),
                        () -> store.releaseRun(threadId, "owner"),
                        () -> store.replacePendingInterrupts(threadId, "owner", Map.of()));
        Class<? extends RuntimeException> expected =
                threadId == null ? NullPointerException.class : IllegalArgumentException.class;
        for (Executable call : calls) {
            assertThrows(expected, call);
            assertStatePreserved(store);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void invalidRunIdIsRejectedWithoutChangingState(String runId) {
        InMemoryAguiResumeStateStore store = seededStore();
        List<Executable> calls =
                List.of(
                        () -> store.claimRun("thread", runId),
                        () -> store.releaseRun("thread", runId),
                        () -> store.replacePendingInterrupts("thread", runId, Map.of()));
        Class<? extends RuntimeException> expected =
                runId == null ? NullPointerException.class : IllegalArgumentException.class;
        for (Executable call : calls) {
            assertThrows(expected, call);
            assertStatePreserved(store);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread", " thread "})
    void validThreadIdIsUsedWithoutNormalization(String threadId) {
        InMemoryAguiResumeStateStore store = new InMemoryAguiResumeStateStore();
        String otherThread = threadId.equals("thread") ? "THREAD" : "thread";
        assertTrue(store.claimRun(threadId, "owner").claimed());
        assertTrue(store.replacePendingInterrupts(threadId, "owner", PENDING));
        assertTrue(store.claimRun(otherThread, "other").claimed());
        assertTrue(store.getPendingInterrupts(otherThread).isEmpty());
        store.releaseRun(otherThread, "other");
        assertEquals("owner", store.claimRun(threadId, "next").activeRunId());
        assertEquals(PENDING, store.getPendingInterrupts(threadId));
        store.releaseRun(threadId, "owner");
        assertTrue(store.claimRun(threadId, "next").claimed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", " owner "})
    void validRunIdIsUsedWithoutNormalization(String runId) {
        InMemoryAguiResumeStateStore store = new InMemoryAguiResumeStateStore();
        String otherRun = runId.equals("owner") ? "OWNER" : "owner";
        assertTrue(store.claimRun("thread", runId).claimed());
        assertTrue(store.replacePendingInterrupts("thread", runId, PENDING));
        store.releaseRun("thread", otherRun);
        assertFalse(store.replacePendingInterrupts("thread", otherRun, Map.of()));
        assertEquals(runId, store.claimRun("thread", otherRun).activeRunId());
        assertEquals(PENDING, store.getPendingInterrupts("thread"));
        store.releaseRun("thread", runId);
        assertTrue(store.claimRun("thread", otherRun).claimed());
        assertEquals(PENDING, store.getPendingInterrupts("thread"));
    }

    private static InMemoryAguiResumeStateStore seededStore() {
        InMemoryAguiResumeStateStore store = new InMemoryAguiResumeStateStore();
        assertTrue(store.claimRun("thread", "owner").claimed());
        assertTrue(store.replacePendingInterrupts("thread", "owner", PENDING));
        return store;
    }

    private static void assertStatePreserved(InMemoryAguiResumeStateStore store) {
        assertEquals("owner", store.claimRun("thread", "next").activeRunId());
        assertEquals(PENDING, store.getPendingInterrupts("thread"));
    }
}
