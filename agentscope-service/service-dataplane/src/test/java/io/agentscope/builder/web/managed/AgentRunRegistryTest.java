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
package io.agentscope.builder.web.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.AgentRun;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentRunRegistryTest {
    @Test
    void cancellationRequiresOwnerAndSessionAndRemovesOnlyItsOwnRun() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRun<String> a = AgentRun.create("agent", Flux::never);
        AgentRun<String> b = AgentRun.create("agent", Flux::never);
        registry.register("alice", "session", a);
        registry.register("alice", "session", b);
        assertThrows(IllegalStateException.class, () -> registry.register("bob", "other", a));
        assertFalse(registry.cancel("bob", "session", b.runId()));
        assertFalse(registry.cancel("alice", "other", b.runId()));
        assertTrue(registry.cancel("alice", "session", b.runId()));
        assertEquals(AgentRun.Status.CREATED, a.status());
        assertTrue(registry.find("alice", "session", a.runId()).isPresent());
        assertTrue(registry.find("alice", "session", b.runId()).isEmpty());
        StepVerifier.create(b.stream())
                .expectError(CancellationException.class)
                .verify(Duration.ofSeconds(2));
        a.cancel();
    }

    @Test
    void completionFailureAndSubscriberDisposalRemoveRegistrations() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRun<String> complete = AgentRun.create("agent", () -> Mono.just("done"));
        AgentRun<String> fail =
                AgentRun.create("agent", () -> Mono.error(new IllegalStateException("failed")));
        AgentRun<String> cancel = AgentRun.create("agent", Flux::never);
        registry.register("alice", "session", complete);
        registry.register("alice", "session", fail);
        registry.register("alice", "session", cancel);
        StepVerifier.create(complete.stream()).expectNext("done").verifyComplete();
        StepVerifier.create(fail.stream()).expectError(IllegalStateException.class).verify();
        cancel.stream().subscribe().dispose();
        assertTrue(registry.find("alice", "session", complete.runId()).isEmpty());
        assertTrue(registry.find("alice", "session", fail.runId()).isEmpty());
        assertTrue(registry.find("alice", "session", cancel.runId()).isEmpty());
    }

    @Test
    void terminalBeforeRegistrationIsImmediatelyRemoved() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRun<String> cancelled = AgentRun.create("agent", Flux::never);
        cancelled.cancel();
        registry.register("alice", "session", cancelled);
        assertTrue(registry.find("alice", "session", cancelled.runId()).isEmpty());
    }
}
