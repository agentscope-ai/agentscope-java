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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class InternalManagedAttemptControllerTest {

    @Test
    void internalAbortCarriesCompleteAttemptFence() {
        SessionTurnRunner runner = mock(SessionTurnRunner.class);
        InternalManagedAttemptController controller = new InternalManagedAttemptController(runner);

        var response =
                controller.abort(
                        "session-a",
                        "attempt-a",
                        request(),
                        new UsernamePasswordAuthenticationToken(
                                "internal",
                                null,
                                List.of(
                                        new SimpleGrantedAuthority(
                                                InternalTokenAuthFilter.ROLE_INTERNAL))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(runner)
                .abortManagedAttempt(
                        "session-a", "task-a", "attempt-a", 3, "turn-a", "attempt_stale");
    }

    @Test
    void abortRejectsNonInternalCaller() {
        InternalManagedAttemptController controller =
                new InternalManagedAttemptController(mock(SessionTurnRunner.class));

        assertThatThrownBy(
                        () ->
                                controller.abort(
                                        "session-a",
                                        "attempt-a",
                                        request(),
                                        new UsernamePasswordAuthenticationToken(
                                                "user",
                                                null,
                                                List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static InternalManagedAttemptController.AbortRequest request() {
        return new InternalManagedAttemptController.AbortRequest(
                "task-a", "attempt-a", 3, "turn-a", "attempt_stale");
    }
}
