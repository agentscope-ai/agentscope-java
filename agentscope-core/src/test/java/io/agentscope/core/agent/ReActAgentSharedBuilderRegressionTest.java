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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.TaskReminderMiddleware;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Regression tests for the shared-builder concurrency bug (issue #2539, PR for 2.x on `main`).
 *
 * <p>Before the fix, {@code ReActAgent.Builder.build()} mutated the shared builder's
 * {@code hooks} / {@code middlewares} collections (long-term-memory / RAG / skill-box / task-list
 * / dynamic-skills configuration). Reusing a shared builder raced on the non-thread-safe
 * collections and, because those hooks don't override {@code equals}/{@code hashCode}, leaked one
 * fresh instance per build — so rebuilding yielded agents carrying 2/3/… copies of each hook.
 *
 * <p>These tests are deterministic (no threads): rebuilding the same builder must yield agents
 * with exactly one of each configured hook/middleware, every time.
 */
@DisplayName("ReActAgent shared-builder rebuild regression (issue #2539)")
class ReActAgentSharedBuilderRegressionTest {

    @Test
    @DisplayName("Rebuilding a shared builder must not accumulate hooks or middlewares")
    void rebuildFromSharedBuilderDoesNotAccumulateHooksOrMiddlewares() {
        ReActAgent.Builder shared =
                ReActAgent.builder()
                        .name("shared")
                        .model(stubModel())
                        .enableTaskList() // -> TaskReminderMiddleware
                        .longTermMemory(stubLongTermMemory()) // -> StaticLongTermMemoryHook
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .knowledge(stubKnowledge()) // -> GenericRAGHook
                        .ragMode(RAGMode.GENERIC);

        // First build establishes the baseline; subsequent rebuilds of the SAME builder must each
        // carry exactly one copy of every configured hook/middleware (no accumulation).
        assertSingleOfEach(shared.build());
        for (int i = 0; i < 3; i++) {
            assertSingleOfEach(shared.build());
        }
    }

    private static void assertSingleOfEach(ReActAgent agent) {
        assertEquals(
                1,
                countInstances(agent.getHooks(), StaticLongTermMemoryHook.class),
                "StaticLongTermMemoryHook count");
        assertEquals(
                1, countInstances(agent.getHooks(), GenericRAGHook.class), "GenericRAGHook count");
        assertEquals(
                1,
                countInstances(agent.getMiddlewares(), TaskReminderMiddleware.class),
                "TaskReminderMiddleware count");
    }

    private static <T> long countInstances(List<?> list, Class<T> type) {
        return list.stream().filter(type::isInstance).count();
    }

    // ---- stubs (mirror the existing ReActAgentBuilderLegacyShimTest helpers) ----

    private static ChatModelBase stubModel() {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "stub";
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }
        };
    }

    private static LongTermMemory stubLongTermMemory() {
        return new LongTermMemory() {
            @Override
            public Mono<Void> record(List<Msg> messages) {
                return Mono.empty();
            }

            @Override
            public Mono<String> retrieve(Msg query) {
                return Mono.empty();
            }
        };
    }

    private static Knowledge stubKnowledge() {
        return new Knowledge() {
            @Override
            public Mono<Void> addDocuments(List<Document> documents) {
                return Mono.empty();
            }

            @Override
            public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                return Mono.just(List.of());
            }
        };
    }
}
