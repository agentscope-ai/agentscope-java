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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ReActSessionDeleteTest {
    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private static class CaptureModel implements Model {
        public String getModelName() {
            return "fake";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    private ReActAgent nativeAgent(SessionLogStore store) {
        return ReActAgent.builder()
                .name("stable")
                .model(new CaptureModel())
                .sessionLogStore(store)
                .build();
    }

    @Test
    void deleteRemovesTheSessionNamespaceAndItsDiscoveryEntry() {
        var store = new InMemorySessionLogStore();
        var agent = nativeAgent(store);
        var other = RuntimeContext.builder().userId("u").sessionId("other").build();
        agent.call(List.of(new UserMessage("delete me")), context()).block(Duration.ofSeconds(10));
        agent.call(List.of(new UserMessage("keep me")), other).block(Duration.ofSeconds(10));
        assertEquals(2, store.list(context()).size());

        agent.deleteSession(context());

        assertTrue(store.list(context()).stream().noneMatch(k -> k.sessionId().equals("s")));
        assertTrue(store.list(context()).stream().anyMatch(k -> k.sessionId().equals("other")));
        // The deleted session restarts from a fresh journal on the next call.
        var events = store.open(new SessionKey("u", "stable", "s"), context()).readAfter(0, 1000);
        assertTrue(events.isEmpty());
    }

    @Test
    void deleteRejectsWhileAWriterHoldsTheLease() {
        var store = new InMemorySessionLogStore();
        var agent = nativeAgent(store);
        agent.call(List.of(new UserMessage("hello")), context()).block(Duration.ofSeconds(10));
        var key = new SessionKey("u", "stable", "s");
        var log = store.open(key, context());
        var writer = log.acquire("external-writer", Duration.ofMinutes(2));

        assertThrows(SessionLogException.class, () -> agent.deleteSession(context()));
        // The session survives a rejected deletion.
        assertFalse(store.list(context()).isEmpty());
        log.release(writer);
        agent.deleteSession(context());
        assertTrue(store.list(context()).stream().noneMatch(k -> k.sessionId().equals("s")));
    }

    @Test
    void deleteRequiresNativeSessionHistory() {
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionHistoryMode(SessionHistoryMode.LEGACY)
                        .build();
        assertThrows(IllegalStateException.class, () -> agent.deleteSession(context()));
        assertThrows(IllegalStateException.class, () -> agent.deleteSession("u", "s"));
    }
}
