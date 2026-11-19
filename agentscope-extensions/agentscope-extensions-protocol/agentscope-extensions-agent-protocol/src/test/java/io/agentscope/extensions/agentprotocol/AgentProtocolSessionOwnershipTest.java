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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * A client-named session is a resource with an owner.
 *
 * <p>Before sessions were named by the caller, the server derived them from a task id the caller had
 * just created, so an unknown value simply meant "new conversation". Now the caller can repeat a
 * value it did not create, and the store has to distinguish three cases: first use (register), same
 * owner (continue), different owner (reject). This test pins all three at the store boundary, plus
 * the cross-instance case where the verdict must come from shared state rather than memory.
 */
@DisplayName("AgentProtocolTaskStore session ownership")
class AgentProtocolSessionOwnershipTest {

    private static final String SESSION = "sub-shared";

    @TempDir Path tempDir;

    private HarnessAgent agent;
    private ProtocolTaskRunRegistry runRegistry;
    private ProtocolSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.<AgentEvent>just(
                                new AgentResultEvent(reply("done")), new AgentEndEvent(null)));
        runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        sessionRegistry = BaseStoreProtocolSessionRegistry.inMemory();
    }

    @Test
    @Timeout(30)
    void firstUseRegistersTheSessionAndTheRunLandsInIt() throws Exception {
        AgentProtocolTaskStore store = store();

        store.submit("t-first", "worker", "hello", context("u-1", SESSION));
        assertEquals("success", store.waitFor("t-first", 20_000).get("status"));

        assertEquals(
                SESSION,
                store.snapshot("t-first").get("session_id"),
                "the client-named session must be the one the run reports");
    }

    @Test
    @Timeout(30)
    void theSameOwnerContinuesTheSession() throws Exception {
        AgentProtocolTaskStore store = store();
        store.submit("t-1", "worker", "first", context("u-1", SESSION));
        assertEquals("success", store.waitFor("t-1", 20_000).get("status"));

        store.submit("t-2", "worker", "second", context("u-1", SESSION));

        assertEquals("success", store.waitFor("t-2", 20_000).get("status"));
    }

    @Test
    @Timeout(30)
    void anotherUserCannotContinueTheSession() throws Exception {
        AgentProtocolTaskStore store = store();
        store.submit("t-1", "worker", "first", context("u-1", SESSION));
        assertEquals("success", store.waitFor("t-1", 20_000).get("status"));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> store.submit("t-2", "worker", "second", context("u-2", SESSION)));

        assertTrue(error.getMessage().contains("session_owner_mismatch"), error.getMessage());
    }

    @Test
    @Timeout(30)
    void anotherAgentCannotContinueTheSession() throws Exception {
        AgentProtocolTaskStore store = store();
        store.submit("t-1", "worker", "first", context("u-1", SESSION));
        assertEquals("success", store.waitFor("t-1", 20_000).get("status"));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> store.submit("t-2", "reviewer", "second", context("u-1", SESSION)));
        assertTrue(error.getMessage().contains("session_owner_mismatch"), error.getMessage());
    }

    @Test
    @Timeout(30)
    void anotherInstanceRejectsAForeignOwnerFromSharedState() throws Exception {
        AgentProtocolTaskStore first = store();
        first.submit("t-1", "worker", "first", context("u-1", SESSION));
        assertEquals("success", first.waitFor("t-1", 20_000).get("status"));

        // A second store over the same registry: the record is the only thing telling it who owns
        // the session, which is exactly what a second instance has.
        AgentProtocolTaskStore other = store();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> other.submit("t-2", "worker", "second", context("u-2", SESSION)));
        assertTrue(error.getMessage().contains("session_owner_mismatch"), error.getMessage());
    }

    private AgentProtocolTaskStore store() {
        return new AgentProtocolTaskStore(
                AgentFactory.fixed(agent),
                new WorkspaceProtocolTaskRepository(tempDir),
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties(),
                List.of(),
                runRegistry,
                BaseStoreProtocolTaskContextStore.inMemory(),
                sessionRegistry);
    }

    private static Map<String, Object> context(String userId, String sessionId) {
        return Map.of("user_id", userId, "session_id", sessionId);
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }
}
