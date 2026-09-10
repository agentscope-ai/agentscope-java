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
package io.agentscope.builder.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class InternalToolConfirmationControllerTest {

    @Test
    void rejectsAuthenticatedNonInternalCaller() {
        InternalToolConfirmationController controller =
                new InternalToolConfirmationController(mock(ToolConfirmationCoordinator.class));
        var user =
                new UsernamePasswordAuthenticationToken(
                        "user", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThatThrownBy(() -> controller.decide("session-a", "tool-a", request(), user))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void internalCallerCanDeliverCancelledDecision() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        when(coordinator.resolveManaged(any()))
                .thenReturn(ToolConfirmationCoordinator.DecisionResult.RESOLVED);
        InternalToolConfirmationController controller =
                new InternalToolConfirmationController(coordinator);
        var internal =
                new UsernamePasswordAuthenticationToken(
                        "internal",
                        null,
                        java.util.List.of(
                                new SimpleGrantedAuthority(InternalTokenAuthFilter.ROLE_INTERNAL)));

        var response = controller.decide("session-a", "tool-a", request(), internal).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ArgumentCaptor<ToolConfirmationCoordinator.ManagedDecision> decision =
                ArgumentCaptor.forClass(ToolConfirmationCoordinator.ManagedDecision.class);
        verify(coordinator).resolveManaged(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo("cancelled");
        assertThat(decision.getValue().allow()).isFalse();
    }

    @Test
    void reportsGoneWhenManagedContinuationWasLost() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        when(coordinator.resolveManaged(any()))
                .thenReturn(ToolConfirmationCoordinator.DecisionResult.CONTINUATION_LOST);
        InternalToolConfirmationController controller =
                new InternalToolConfirmationController(coordinator);
        var internal =
                new UsernamePasswordAuthenticationToken(
                        "internal",
                        null,
                        java.util.List.of(
                                new SimpleGrantedAuthority(InternalTokenAuthFilter.ROLE_INTERNAL)));

        assertThatThrownBy(
                        () -> controller.decide("session-a", "tool-a", request(), internal).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void asksControlPlaneToRetryUntilContinuationOwnerAcknowledges() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        when(coordinator.resolveManaged(any()))
                .thenReturn(ToolConfirmationCoordinator.DecisionResult.OWNER_UNAVAILABLE);
        InternalToolConfirmationController controller =
                new InternalToolConfirmationController(coordinator);
        var internal =
                new UsernamePasswordAuthenticationToken(
                        "internal",
                        null,
                        java.util.List.of(
                                new SimpleGrantedAuthority(InternalTokenAuthFilter.ROLE_INTERNAL)));

        assertThatThrownBy(
                        () -> controller.decide("session-a", "tool-a", request(), internal).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex ->
                                assertThat(ex.getStatusCode())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static InternalToolConfirmationController.DecisionRequest request() {
        return new InternalToolConfirmationController.DecisionRequest(
                "approval-a",
                2,
                "cancelled",
                false,
                "timed_out",
                "task-a",
                "attempt-a",
                3,
                "turn-a");
    }
}
