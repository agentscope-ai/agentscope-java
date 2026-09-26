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
package io.agentscope.extensions.redis.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.UnifiedJedis;

class RedisAguiResumeStateStoreTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void invalidThreadIdIsRejectedBeforeRedisAccess(String threadId) {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");
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
            // No reads or writes can disturb any existing Redis owner or pending interrupts.
            verifyNoInteractions(jedis);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void invalidRunIdIsRejectedBeforeRedisAccess(String runId) {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");
        List<Executable> calls =
                List.of(
                        () -> store.claimRun("thread", runId),
                        () -> store.releaseRun("thread", runId),
                        () -> store.replacePendingInterrupts("thread", runId, Map.of()));
        Class<? extends RuntimeException> expected =
                runId == null ? NullPointerException.class : IllegalArgumentException.class;
        for (Executable call : calls) {
            assertThrows(expected, call);
            verifyNoInteractions(jedis);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread", " thread "})
    void validThreadIdIsUsedWithoutNormalization(String threadId) {
        assertRawIdentifiers(threadId, "owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", " owner "})
    void validRunIdIsUsedWithoutNormalization(String runId) {
        assertRawIdentifiers("thread", runId);
    }

    private static void assertRawIdentifiers(String threadId, String runId) {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(null, 1L, 1L);
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");
        assertTrue(store.getPendingInterrupts(threadId).isEmpty());
        assertTrue(store.claimRun(threadId, runId).claimed());
        assertTrue(store.replacePendingInterrupts(threadId, runId, Map.of()));
        store.releaseRun(threadId, runId);
        verify(jedis).hget("test:" + threadId, "pendingInterrupts");
        verify(jedis, times(2))
                .eval(
                        anyString(),
                        eq(List.of("test:" + threadId)),
                        eq(List.of("activeRunId", runId)));
        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("test:" + threadId)),
                        eq(List.of("activeRunId", runId, "pendingInterrupts", "1", "")));
    }

    @Test
    void readsPendingInterruptsFromSharedState() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        AguiEvent.Interrupt interrupt = interrupt("interrupt-1");
        when(jedis.hget("test:thread-1", "pendingInterrupts"))
                .thenReturn(JsonUtils.getJsonCodec().toJson(Map.of(interrupt.id(), interrupt)));
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test");

        Map<String, AguiEvent.Interrupt> result = store.getPendingInterrupts("thread-1");

        assertEquals(Map.of("interrupt-1", interrupt), result);
    }

    @Test
    void claimRunReportsWhetherRedisCreatedTheOwnerField() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.eval(anyString(), anyList(), anyList()))
                .thenReturn(null)
                .thenReturn("run-1".getBytes(StandardCharsets.UTF_8));
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");

        AguiResumeStateStore.RunClaim acquired = store.claimRun("thread-1", "run-1");
        AguiResumeStateStore.RunClaim rejected = store.claimRun("thread-1", "run-2");

        assertTrue(acquired.claimed());
        assertFalse(rejected.claimed());
        assertEquals("run-1", rejected.activeRunId());
    }

    @Test
    void releaseUsesOneOwnerCheckedRedisOperation() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");

        store.releaseRun("thread-1", "run-1");

        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("test:thread-1")),
                        eq(List.of("activeRunId", "run-1")));
    }

    @Test
    void pendingReplacementReturnsOwnerCheckResultAndClearsWithSameOperation() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L).thenReturn(0L);
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis, "test:");

        assertTrue(
                store.replacePendingInterrupts(
                        "thread-1", "run-1", Map.of("interrupt-1", interrupt("interrupt-1"))));
        assertFalse(store.replacePendingInterrupts("thread-1", "run-2", Map.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis, times(2))
                .eval(anyString(), eq(List.of("test:thread-1")), argsCaptor.capture());
        assertEquals("1", argsCaptor.getAllValues().get(1).get(3));
    }

    @Test
    void redisFailureIsPropagated() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.hget(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedisAguiResumeStateStore store = new RedisAguiResumeStateStore(jedis);

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> store.getPendingInterrupts("thread-1"));

        assertEquals("redis unavailable", error.getMessage());
    }

    private static AguiEvent.Interrupt interrupt(String interruptId) {
        return new AguiEvent.Interrupt(
                interruptId,
                "tool_call",
                "approve",
                "tool-call-1",
                null,
                null,
                Map.of("source", "server"));
    }
}
