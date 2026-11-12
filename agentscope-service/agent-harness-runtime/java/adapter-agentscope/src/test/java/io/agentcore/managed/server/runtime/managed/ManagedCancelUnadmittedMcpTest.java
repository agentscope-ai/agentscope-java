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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedCancelUnadmittedMcpTest {
    private static final String TOOL = "terminal__terminal_poll";
    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private final ManagedTurnExecution execution = mock(ManagedTurnExecution.class);
    private final ManagedJournalStore journal = mock(ManagedJournalStore.class);
    private final ManagedToolTransport transport = mock(ManagedToolTransport.class);
    private final ManagedLocalJournal local = mock(ManagedLocalJournal.class);
    private final ManagedInputDelivery inputs = mock(ManagedInputDelivery.class);
    private ManagedMqSessionLog log;

    @BeforeEach
    void dispatchedButNotAdmitted() {
        var nativeLog = new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), null);
        log = mock(ManagedMqSessionLog.class, delegatesTo(nativeLog));
        var call =
                ToolUseBlock.builder()
                        .id("call")
                        .name(TOOL)
                        .input(Map.of("operationId", "old"))
                        .content("{\"operationId\":\"old\"}")
                        .build();
        var state = AgentState.builder().build();
        state.contextMutable().add(AssistantMessage.builder().content(call).build());
        var writer = nativeLog.acquire("original", Duration.ofMinutes(1));
        nativeLog.commit(
                writer,
                "original",
                0,
                List.of(
                        event(1, "state/checkpoint", Map.of("stateJson", state.toJson())),
                        event(2, "tool/dispatch", Map.of("toolCallId", "call", "name", TOOL))));
        nativeLog.release(writer);
        assertEquals(Set.of("call"), SessionProjection.read(log).uncertainToolCalls());
        when(execution.scope()).thenReturn(scope);
        when(execution.turnId()).thenReturn("turn");
        when(execution.log()).thenReturn(log);
        when(execution.journal()).thenReturn(journal);
        when(execution.local()).thenReturn(local);
        when(journal.transport()).thenReturn(transport);
        when(journal.cancelRequested(scope, "turn")).thenReturn(true);
        when(journal.mcpBatchForCall(scope, "turn", "call")).thenReturn(Optional.empty());
    }

    @Test
    void recordsInterruptedNotSuccessfulOutcomeWithoutInvokingOrPreparingTools() {
        var turns =
                new ManagedHarnessTurns(
                        new ObjectMapper(), inputs, mock(ScheduledExecutorService.class));
        // No agent, model or tool initialization exists on this path. A task budget may
        // already have expired; only the fresh execution ownership fence is checked.
        assertTrue(turns.cancelBeforePreparation(execution, () -> Set.of(TOOL)));
        var projection = SessionProjection.read(log);
        assertTrue(projection.uncertainToolCalls().isEmpty());
        var results =
                projection.restore().contextMutable().stream()
                        .flatMap(m -> m.getContent().stream())
                        .filter(ToolResultBlock.class::isInstance)
                        .map(ToolResultBlock.class::cast)
                        .toList();
        assertEquals(1, results.size());
        assertEquals(ToolResultState.INTERRUPTED, results.get(0).getState());
        verify(execution).finish("cancelled");
        verify(transport, never()).send(any());
        verify(execution, atLeastOnce()).check();
    }

    @Test
    void anAdmittedSingleCallCannotBeInventedAsCancelledBeforeAdmission() {
        when(journal.toolsForNativeCall(scope, "turn", "call"))
                .thenReturn(List.of(mock(ManagedJournalStore.ToolTask.class)));
        assertRefused();
    }

    @Test
    void anAdmittedBatchCannotBeInventedAsCancelledBeforeAdmission() {
        when(journal.mcpBatchForCall(scope, "turn", "call"))
                .thenReturn(Optional.of(mock(ManagedJournalStore.ToolTask.class)));
        assertRefused();
    }

    @Test
    void uncommittedProposalIsStillAmbiguous() {
        when(transport.pending(scope, "turn")).thenReturn(List.of(mock(ManagedToolCommand.class)));
        assertRefused();
    }

    @Test
    void unknownToolDoesNotInheritTheManagedMcpAdmissionGuarantee() {
        assertFalse(ManagedNativeCancellation.cancelUnadmittedMcp(execution, Set.of("other")));
        assertEquals(2, log.head().seq());
    }

    @Test
    void lostExecutionFenceCannotCommitCancellation() {
        doThrow(new ManagedTransportException("lost epoch")).when(execution).check();
        assertThrows(
                ManagedTransportException.class,
                () -> ManagedNativeCancellation.cancelUnadmittedMcp(execution, Set.of(TOOL)));
        assertEquals(2, log.head().seq());
    }

    @Test
    void noCancelRequestCannotConvertDispatchToCancellation() {
        when(journal.cancelRequested(scope, "turn")).thenReturn(false);
        assertRefused();
    }

    private void assertRefused() {
        assertFalse(ManagedNativeCancellation.cancelUnadmittedMcp(execution, Set.of(TOOL)));
        assertEquals(2, log.head().seq());
        verify(execution, never()).finish(anyString());
    }

    private SessionEvent event(long seq, String type, Object payload) {
        return new SessionEvent(
                1,
                "e" + seq,
                seq,
                1,
                type,
                "original",
                "turn",
                true,
                JsonUtils.getJsonCodec().toJson(payload));
    }
}
