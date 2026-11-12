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

package io.agentscope.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionRecoveryTest {
    private final Executor executor = mock(Executor.class);
    private final ExecutionRequest request =
            new ExecutionRequest(
                    "effect",
                    new ThreadKey(new SessionKey("n", "s"), "main"),
                    "turn",
                    new ToolCall("call", "remote", "operation", DurableJson.value(Map.of())));
    private final ExecutionResult unknown = ExecutionResult.unknown("start response lost");
    private final ExecutionResult running =
            new ExecutionResult(ExecutionState.RUNNING, "recovered", null, null);
    private final ExecutionResult cancelled =
            new ExecutionResult(ExecutionState.CANCELLED, null, null, null);

    @Test
    void firstDispatchDoesNotDependOnASeparateCapabilityRequest() {
        when(executor.startExecution(request)).thenReturn(running);
        assertEquals(running, ExecutionRecovery.resolve(executor, request, unknown, false, false));
        verify(executor, never()).capabilities();
    }

    @Test
    void cancellationUsesAndRetainsTheHandleRecoveredByQuery() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(executor.query(request, null)).thenReturn(running);
        when(executor.cancel(request, "recovered")).thenReturn(cancelled);
        var result = ExecutionRecovery.resolve(executor, request, unknown, true, true);
        assertEquals(ExecutionState.CANCELLED, result.state());
        assertEquals("recovered", result.handle());
        verify(executor).cancel(request, "recovered");
        verify(executor, never()).startExecution(any());
    }

    @Test
    void failedCancellationStillPreservesTheNewlyLocatedOperation() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(executor.query(request, null)).thenReturn(running);
        when(executor.cancel(request, "recovered"))
                .thenThrow(new IllegalStateException("lost cancel reply"));
        var result = ExecutionRecovery.resolve(executor, request, unknown, true, true);
        assertEquals(ExecutionState.UNKNOWN, result.state());
        assertEquals("recovered", result.handle());
    }

    @Test
    void unavailableQueryDoesNotPreventAnIndependentCancellation() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        when(executor.query(request, "recovered"))
                .thenThrow(new IllegalStateException("query unavailable"));
        when(executor.cancel(request, "recovered")).thenReturn(cancelled);
        assertEquals(
                ExecutionState.CANCELLED,
                ExecutionRecovery.resolve(executor, request, running, true, true).state());
    }

    @Test
    void aCompletedOperationWinsOverACancellationRequest() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(false, true, true));
        var completed =
                new ExecutionResult(
                        ExecutionState.SUCCEEDED, "recovered", DurableJson.value("done"), null);
        when(executor.query(request, null)).thenReturn(completed);
        assertEquals(completed, ExecutionRecovery.resolve(executor, request, unknown, true, true));
        verify(executor, never()).cancel(any(), any());
    }

    @Test
    void anIdempotentProviderWithoutLookupCanReuseTheOriginalStartIdentity() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(true, false, false));
        when(executor.startExecution(request)).thenReturn(running);
        assertEquals(running, ExecutionRecovery.resolve(executor, request, unknown, true, false));
        verify(executor).startExecution(eq(request));
        verify(executor, never()).query(any(), any());
    }

    @Test
    void uncertainNonIdempotentEffectsAreNeverRepeated() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(false, false, false));
        assertEquals(
                ExecutionState.UNKNOWN,
                ExecutionRecovery.resolve(executor, request, unknown, true, false).state());
        verify(executor, never()).startExecution(any());
        verify(executor, never()).query(any(), any());
    }

    @Test
    void missingOriginalOperationDoesNotProveCancellation() {
        when(executor.capabilities()).thenReturn(new Executor.Capabilities(true, true, true));
        when(executor.query(request, null)).thenReturn(ExecutionResult.missing());
        when(executor.cancel(request, null)).thenReturn(ExecutionResult.missing());
        assertEquals(
                ExecutionState.UNKNOWN,
                ExecutionRecovery.resolve(executor, request, unknown, true, true).state());
        verify(executor, never()).startExecution(any());
    }

    @Test
    void cancellationBeforeDispatchDoesNotTouchTheProvider() {
        assertEquals(
                ExecutionState.CANCELLED,
                ExecutionRecovery.resolve(executor, request, unknown, false, true).state());
        verifyNoInteractions(executor);
    }
}
