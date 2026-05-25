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
package io.agentscope.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.state.AgentState;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StorageBaseTest {

    /** Custom backend used to verify {@link StorageBase} can be subclassed. */
    private static final class RecordingStorage extends StorageBase {
        final ConcurrentHashMap<String, AgentState> store = new ConcurrentHashMap<>();

        @Override
        public Mono<AgentState> loadAgentState(String sessionId, String agentId) {
            AgentState s = store.get(sessionId + '|' + agentId);
            return s == null ? Mono.empty() : Mono.just(s);
        }

        @Override
        public Mono<Void> saveAgentState(String sessionId, String agentId, AgentState state) {
            return Mono.fromRunnable(() -> store.put(sessionId + '|' + agentId, state));
        }
    }

    @Test
    void abstractContractAllowsCustomSubclass() {
        RecordingStorage storage = new RecordingStorage();
        AgentState state = AgentState.builder().summary("custom").build();

        StepVerifier.create(storage.saveAgentState("s", "a", state)).verifyComplete();
        StepVerifier.create(storage.loadAgentState("s", "a"))
                .assertNext(loaded -> assertEquals("custom", loaded.getSummary()))
                .verifyComplete();
        StepVerifier.create(storage.loadAgentState("missing", "missing")).verifyComplete();
    }
}
