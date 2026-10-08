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
package io.agentscope.spring.boot.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.spring.boot.admin.dto.CompactRequest;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.registry.InMemoryAgentRegistry;
import io.agentscope.spring.boot.admin.snapshot.SnapshotStore;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionOperationsTest {

    private final SummarizationStrategy noopSummarizer =
            (model, existing, fold) -> Mono.just(existing == null ? "" : existing);

    private SessionOperations ops(
            InMemoryAgentRegistry registry,
            io.agentscope.spring.boot.admin.snapshot.SnapshotStore snapshots) {
        return new SessionOperations(registry, noopSummarizer, new AdminProperties(), snapshots);
    }

    @Test
    void listSessionsReturnsEmptyWhenNoSessionBean() {
        SessionOperations ops =
                new SessionOperations(
                        new InMemoryAgentRegistry(),
                        noopSummarizer,
                        new AdminProperties(),
                        new io.agentscope.spring.boot.admin.snapshot.SnapshotStore());
        StepVerifier.create(ops.listSessions(null))
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void resolveErrorsWhenAgentMissing() {
        SessionOperations ops =
                new SessionOperations(
                        new InMemoryAgentRegistry(),
                        noopSummarizer,
                        new AdminProperties(),
                        new io.agentscope.spring.boot.admin.snapshot.SnapshotStore());
        StepVerifier.create(ops.listMessages("unknown"))
                .expectErrorSatisfies(
                        err ->
                                assertThat(err)
                                        .isInstanceOf(NoSuchElementException.class)
                                        .hasMessageContaining("unknown"))
                .verify();
    }

    @Test
    void abortInvokesInterruptOnRegisteredAgent() {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn("agent-1");
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(agent);
        SessionOperations ops =
                ops(registry, new io.agentscope.spring.boot.admin.snapshot.SnapshotStore());

        StepVerifier.create(ops.abort("agent-1")).verifyComplete();
        verify(agent, times(1)).interrupt();
    }

    @Test
    void abortErrorsWhenAgentUnknown() {
        SessionOperations ops =
                new SessionOperations(
                        new InMemoryAgentRegistry(),
                        noopSummarizer,
                        new AdminProperties(),
                        new io.agentscope.spring.boot.admin.snapshot.SnapshotStore());
        StepVerifier.create(ops.abort("ghost")).expectError(NoSuchElementException.class).verify();
    }

    @Test
    void compactSkipsWhenContextChangesDuringSummarization() {
        Msg first = userMessage("first");
        Msg second = userMessage("second");
        Msg changed = userMessage("changed");
        AgentState state =
                AgentState.builder()
                        .sessionId("session-1")
                        .summary("old summary")
                        .context(List.of(first, second))
                        .build();
        ReActAgent react = mock(ReActAgent.class);
        when(react.getAgentId()).thenReturn("agent-1");
        when(react.getAgentState()).thenReturn(state);

        SummarizationStrategy mutatingSummarizer =
                (model, existing, fold) -> {
                    state.replaceContext(List.of(changed));
                    return Mono.just("new summary");
                };
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(react);
        SessionOperations ops =
                new SessionOperations(
                        registry, mutatingSummarizer, new AdminProperties(), new SnapshotStore());

        StepVerifier.create(ops.compact("agent-1", new CompactRequest(1, true)))
                .assertNext(
                        response -> {
                            assertThat(response.messagesBefore()).isEqualTo(2);
                            assertThat(response.messagesAfter()).isEqualTo(1);
                            assertThat(response.summaryLengthBefore())
                                    .isEqualTo("old summary".length());
                            assertThat(response.summaryLengthAfter())
                                    .isEqualTo("old summary".length());
                        })
                .verifyComplete();

        assertThat(state.getSummary()).isEqualTo("old summary");
        assertThat(state.getContext()).containsExactly(changed);
    }

    private static Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
