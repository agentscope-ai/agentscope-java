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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ReActAgentObservePersistenceTest {
    private static final String SESSION = "observe-session";
    private static final String NOTE = "A teammate reported that the draft is ready";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory", "json"})
    void observedMessageReachesTheNextModelRequest(String backend) {
        AgentStateStore store =
                switch (backend) {
                    case "memory" -> new InMemoryAgentStateStore();
                    case "json" -> new JsonFileAgentStateStore(directory);
                    default -> null;
                };
        RecordingModel model = new RecordingModel();
        try (ReActAgent agent = builder(store, model).build()) {
            assertNotNull(agent.call(new UserMessage("Start the collaboration")).block(TIMEOUT));
            agent.observe(note(NOTE)).block(TIMEOUT);

            assertEquals(1, model.requests.get(), "observe must not invoke the model");
            assertTrue(texts(agent).contains(NOTE), "observe must append the message");
            assertNotNull(agent.call(new UserMessage("Use the teammate's report")).block(TIMEOUT));
            assertEquals(2, model.requests.get());
            assertEquals(SESSION, agent.getAgentState(null, SESSION).getSessionId());
            assertTrue(
                    model.lastMessages.get().stream()
                            .anyMatch(message -> NOTE.equals(message.getTextContent())),
                    "The next model request must include the observed message");
        }
    }

    @ParameterizedTest
    @CsvSource({"json,1", "json,2", "journal,1", "journal,2"})
    void observationsArePersistedBeforeCompletionWithoutAnEarlierCall(String backend, int count) {
        RecordingModel model = new RecordingModel();
        ReActAgent.Builder agentBuilder =
                backend.equals("json")
                        ? builder(new JsonFileAgentStateStore(directory), model)
                        : builder(null, model).threadJournalStore(new InMemoryThreadJournalStore());
        List<Msg> messages =
                count == 1 ? List.of(note("first")) : List.of(note("first"), note("second"));
        try (ReActAgent agent = agentBuilder.build()) {
            Mono<Void> observation =
                    count == 1 ? agent.observe(messages.get(0)) : agent.observe(messages);
            observation.block(TIMEOUT);
        }
        try (ReActAgent reloaded = agentBuilder.build()) {
            assertEquals(messages.stream().map(Msg::getTextContent).toList(), texts(reloaded));
            assertEquals(0, model.requests.get());
        }
    }

    @Test
    void storageFailureIsReportedOnSubscriptionWithoutInvokingTheModel() {
        AtomicInteger writes = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("test storage failure");
        JsonFileAgentStateStore store =
                new JsonFileAgentStateStore(directory) {
                    @Override
                    public void save(String userId, String sessionId, String key, State value) {
                        writes.incrementAndGet();
                        throw failure;
                    }
                };
        RecordingModel model = new RecordingModel();
        try (ReActAgent agent = builder(store, model).build()) {
            Mono<Void> observation = agent.observe(note(NOTE));
            assertEquals(0, writes.get(), "Creating the publisher must not write storage");
            StepVerifier.create(observation)
                    .expectErrorMatches(error -> error == failure)
                    .verify(TIMEOUT);
            assertEquals(1, writes.get());
            assertEquals(0, model.requests.get());
        }
    }

    @Test
    void observationDoesNotReadOrWriteStorageOnANonBlockingThread() {
        AtomicBoolean nonBlockingIo = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        JsonFileAgentStateStore store =
                new JsonFileAgentStateStore(directory) {
                    @Override
                    public <T extends State> Optional<T> get(
                            String userId, String sessionId, String key, Class<T> type) {
                        if (Schedulers.isInNonBlockingThread()) nonBlockingIo.set(true);
                        return super.get(userId, sessionId, key, type);
                    }

                    @Override
                    public void save(String userId, String sessionId, String key, State value) {
                        if (Schedulers.isInNonBlockingThread()) nonBlockingIo.set(true);
                        writes.incrementAndGet();
                        super.save(userId, sessionId, key, value);
                    }
                };
        try (ReActAgent agent = builder(store, new RecordingModel()).build()) {
            Mono.defer(() -> agent.observe(note(NOTE)))
                    .subscribeOn(Schedulers.parallel())
                    .block(TIMEOUT);
            assertEquals(1, writes.get());
            assertFalse(nonBlockingIo.get(), "Storage I/O must leave the reactive event loop");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ConflictPolicy.class,
            names = {"FAIL", "APPEND_MERGE"})
    void observationHonorsTheConfiguredVersionConflictPolicy(ConflictPolicy policy) {
        DetachedVersionedStore store = new DetachedVersionedStore();
        RecordingModel model = new RecordingModel();
        try (ReActAgent observer = builder(store, model).conflictPolicy(policy).build();
                ReActAgent writer = builder(store, model).build()) {
            // The observer keeps an older baseline while another instance commits normally.
            assertEquals(List.of(), texts(observer));
            writer.getAgentState(null, SESSION).contextMutable().add(note("remote message"));
            writer.saveAgentState(null, SESSION);

            if (policy == ConflictPolicy.FAIL) {
                StepVerifier.create(observer.observe(note(NOTE)))
                        .expectError(ConcurrentSessionModificationException.class)
                        .verify(TIMEOUT);
            } else {
                observer.observe(note(NOTE)).block(TIMEOUT);
                assertEquals(List.of("remote message", NOTE), texts(observer));
            }
            try (ReActAgent reloaded = builder(store, model).build()) {
                assertEquals(
                        policy == ConflictPolicy.FAIL
                                ? List.of("remote message")
                                : List.of("remote message", NOTE),
                        texts(reloaded));
            }
            assertEquals(1, observer.getStateConflictCount());
            assertEquals(0, model.requests.get());
        }
    }

    private static ReActAgent.Builder builder(AgentStateStore store, RecordingModel model) {
        ReActAgent.Builder builder =
                ReActAgent.builder().name("observer").defaultSessionId(SESSION).model(model);
        if (store != null) builder.stateStore(store);
        return builder;
    }

    private static Msg note(String text) {
        return AssistantMessage.builder().name("teammate").textContent(text).build();
    }

    private static List<String> texts(ReActAgent agent) {
        return agent.getAgentState(null, SESSION).getContext().stream()
                .map(Msg::getTextContent)
                .toList();
    }

    /** Copies values as a serializing versioned backend would, avoiding shared-reference masking. */
    private static final class DetachedVersionedStore extends InMemoryAgentStateStore {
        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String userId, String sessionId, String key, Class<T> type) {
            VersionedState<T> result = super.getVersioned(userId, sessionId, key, type);
            return new VersionedState<>(
                    result.isPresent() ? copy(result.value(), type) : null, result.version());
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            return super.saveIfVersion(
                    userId, sessionId, key, copy(value, value.getClass()), expectedVersion);
        }

        private static <T extends State> T copy(State value, Class<T> type) {
            return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(value), type);
        }
    }

    private static final class RecordingModel implements Model {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<List<Msg>> lastMessages = new AtomicReference<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int request = requests.incrementAndGet();
            lastMessages.set(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .id("observation-response-" + request)
                            .content(List.of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "observation-test-model";
        }
    }
}
