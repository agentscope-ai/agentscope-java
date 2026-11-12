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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionLogException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedInlineWaitCapacityTest {
    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private final ManagedTurnExecution execution = mock(ManagedTurnExecution.class);
    private final ManagedExecutionGuard guard = mock(ManagedExecutionGuard.class);
    private final ManagedToolResultDelivery delivery = mock(ManagedToolResultDelivery.class);
    private final ManagedToolRequests requests =
            new ManagedToolRequests(
                    new ObjectMapper(),
                    Duration.ofSeconds(1),
                    delivery,
                    mock(ManagedBatchCodec.class));

    private String request() {
        when(execution.scope()).thenReturn(scope);
        when(execution.turnId()).thenReturn("turn");
        when(execution.guard()).thenReturn(guard);
        var context =
                RuntimeContext.builder()
                        .put(ManagedTurnExecution.class, execution)
                        .put(
                                ManagedToolRequests.Operations.class,
                                new ManagedToolRequests.Operations())
                        .put(
                                ToolUseBlock.class,
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("test")
                                        .input(Map.of())
                                        .build())
                        .build();
        return requests.request(context, "test", Map.of(), String.class);
    }

    @Test
    void lackOfOptionalReadCapacityYieldsOnlyAfterDurableIntentWithoutInvalidatingOwnership() {
        when(delivery.await(any(), anyString(), any()))
                .thenThrow(new ManagedReadCapacityException());
        assertThrows(ManagedToolWaiting.class, this::request);
        var order = inOrder(execution, delivery);
        order.verify(execution).boundary(anyString(), anyList(), anyList(), anyList(), isNull());
        order.verify(delivery).await(eq(scope), anyString(), any());
        verify(guard, never()).invalidate(anyString(), any());
        verify(execution, never()).toolCompleted(anyString(), anyString());
    }

    @Test
    void unknownTransportFailureStillInvalidatesOwnershipInsteadOfPretendingToSuspend() {
        var failure = new ManagedTransportException("Mailbox result is corrupt");
        when(delivery.await(any(), anyString(), any())).thenThrow(failure);
        var error = assertThrows(SessionLogException.class, this::request);
        assertNull(ManagedToolWaiting.find(error));
        verify(guard).invalidate(anyString(), eq(failure));
    }

    @Test
    void capacityFallbackCannotMaskConcurrentExecutionOwnershipLoss() {
        when(delivery.await(any(), anyString(), any()))
                .thenThrow(new ManagedReadCapacityException());
        doNothing().doThrow(new ManagedTransportException("Owner lost")).when(guard).check();
        var error = assertThrows(SessionLogException.class, this::request);
        assertNull(ManagedToolWaiting.find(error));
        verify(guard).invalidate(anyString(), any());
    }

    @Test
    void readCapacityFailureOutsideTheOptionalWaitIsNotConvertedToSuccessfulSuspension() {
        var failure = new ManagedReadCapacityException();
        doThrow(failure)
                .when(execution)
                .boundary(anyString(), anyList(), anyList(), anyList(), isNull());
        var error = assertThrows(SessionLogException.class, this::request);
        assertNull(ManagedToolWaiting.find(error));
        verify(delivery, never()).await(any(), anyString(), any());
        verify(guard).invalidate(anyString(), eq(failure));
    }
}
