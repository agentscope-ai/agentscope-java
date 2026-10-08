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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Regression tests for failures while restoring a {@link ReActAgent} session. */
@DisplayName("ReActAgent state load failures")
class ReActAgentStateLoadFailureTest {

    private static final String USER_ID = "user";
    private static final String SESSION_ID = "session";

    @Test
    @DisplayName("versioned store failures fail the call without overwriting persisted state")
    void versionedStoreFailureFailsCallWithoutOverwritingPersistedState() {
        ThrowingVersionedStore store = new ThrowingVersionedStore();
        AgentState persisted = persistedState();
        store.save(USER_ID, SESSION_ID, "agent_state", persisted);
        int writesBeforeFailure = store.writeCount();
        IllegalStateException failure = new IllegalStateException("simulated backend failure");
        store.failLoadsWith(failure);
        ReActAgent agent = agent(store);
        RuntimeContext context =
                RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(List.of(userMsg("new turn")), context)
                                        .block(Duration.ofSeconds(5)));

        assertSame(failure, thrown, "the original store failure should propagate");
        assertEquals(
                writesBeforeFailure,
                store.writeCount(),
                "a failed load must not trigger a save over persisted history");

        store.recoverLoads();
        AgentState restored = agent.getAgentState(USER_ID, SESSION_ID);
        assertEquals("remembered", restored.getSummary());
        assertEquals(
                List.of("old turn"),
                restored.getContext().stream().map(Msg::getTextContent).toList(),
                "a failed load must not cache a fresh state");
    }

    @Test
    @DisplayName("a genuinely missing state still creates a fresh session")
    void missingStateStillCreatesFreshSession() {
        AgentState fresh = agent(new InMemoryAgentStateStore()).getAgentState(USER_ID, SESSION_ID);

        assertEquals(USER_ID, fresh.getUserId());
        assertEquals(SESSION_ID, fresh.getSessionId());
        assertTrue(fresh.getContext().isEmpty());
    }

    @Test
    @DisplayName("an existing state still loads normally")
    void existingStateStillLoadsNormally() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.save(USER_ID, SESSION_ID, "agent_state", persistedState());

        AgentState restored = agent(store).getAgentState(USER_ID, SESSION_ID);

        assertEquals("remembered", restored.getSummary());
        assertEquals(
                List.of("old turn"),
                restored.getContext().stream().map(Msg::getTextContent).toList());
    }

    private static ReActAgent agent(AgentStateStore store) {
        return ReActAgent.builder()
                .name("assistant")
                .sysPrompt("Be helpful")
                .model(new NoopModel())
                .stateStore(store)
                .build();
    }

    private static AgentState persistedState() {
        return AgentState.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .summary("remembered")
                .context(List.of(userMsg("old turn")))
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static final class NoopModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "noop";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private static final class ThrowingVersionedStore extends InMemoryAgentStateStore {
        private RuntimeException loadFailure;
        private int writeCount;

        void failLoadsWith(RuntimeException failure) {
            loadFailure = failure;
        }

        void recoverLoads() {
            loadFailure = null;
        }

        int writeCount() {
            return writeCount;
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            writeCount++;
            super.save(userId, sessionId, key, value);
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String userId, String sessionId, String key, Class<T> type) {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return super.getVersioned(userId, sessionId, key, type);
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            writeCount++;
            return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        }
    }

    /** Uses the interface's non-versioned getVersioned implementation and fails in legacy reads. */
    private static final class LegacyFailingStore implements AgentStateStore {
        private final InMemoryAgentStateStore delegate = new InMemoryAgentStateStore();
        private RuntimeException legacyLoadFailure;

        void failLegacyLoadsWith(RuntimeException failure) {
            legacyLoadFailure = failure;
        }

        void recoverLegacyLoads() {
            legacyLoadFailure = null;
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            delegate.save(userId, sessionId, key, value);
        }

        @Override
        public void save(
                String userId, String sessionId, String key, List<? extends State> values) {
            delegate.save(userId, sessionId, key, values);
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            return delegate.get(userId, sessionId, key, type);
        }

        @Override
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> itemType) {
            if (legacyLoadFailure != null) {
                throw legacyLoadFailure;
            }
            return delegate.getList(userId, sessionId, key, itemType);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return delegate.exists(userId, sessionId);
        }

        @Override
        public void delete(String userId, String sessionId) {
            delegate.delete(userId, sessionId);
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return delegate.listSessionIds(userId);
        }
    }
}
