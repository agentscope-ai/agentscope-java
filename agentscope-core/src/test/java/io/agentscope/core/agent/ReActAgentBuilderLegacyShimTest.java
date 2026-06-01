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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies the 1.x → 2.0 builder compatibility shims:
 * - deprecated setters wire their legacy hooks/tools into the runtime agent
 * - the hard-deleted setters/methods are confirmed absent via reflection
 */
class ReActAgentBuilderLegacyShimTest {

    /** Minimal stub model that returns a single empty assistant chunk per call. */
    private static final class StubModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }

    private static ChatModelBase stubModel() {
        return new StubModel();
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

    @Test
    @SuppressWarnings("deprecation")
    void planNotebookRegistersPlanTools() {
        PlanNotebook nb = PlanNotebook.builder().build();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("plan-compat")
                        .model(stubModel())
                        .planNotebook(nb)
                        .build();
        // PlanNotebook self-registers a handful of plan-related tools (create_plan, finish_plan,
        // view_subtasks, ...) when registered as a tool object.
        boolean hasPlanTool =
                agent.getToolkit().getToolNames().stream().anyMatch(n -> n.contains("plan"));
        assertTrue(
                hasPlanTool,
                "PlanNotebook tools should be registered, got: "
                        + agent.getToolkit().getToolNames());
    }

    @Test
    @SuppressWarnings("deprecation")
    void longTermMemoryAgentControlRegistersMemoryTools() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ltm-agent-control")
                        .model(stubModel())
                        .longTermMemory(stubLongTermMemory())
                        .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                        .build();
        boolean hasMemoryTool =
                agent.getToolkit().getToolNames().stream()
                        .anyMatch(n -> n.contains("memory") || n.contains("retrieve"));
        assertTrue(
                hasMemoryTool,
                "LongTermMemoryTools should be registered, got: "
                        + agent.getToolkit().getToolNames());
    }

    @Test
    @SuppressWarnings("deprecation")
    void longTermMemoryStaticControlAddsHookWithoutTools() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ltm-static")
                        .model(stubModel())
                        .longTermMemory(stubLongTermMemory())
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .build();
        // STATIC_CONTROL path adds StaticLongTermMemoryHook to the hooks set, doesn't register
        // tools.
        Set<String> toolNames = agent.getToolkit().getToolNames();
        assertFalse(
                toolNames.stream().anyMatch(n -> n.contains("retrieve_memory")),
                "STATIC_CONTROL must not register memory tools: " + toolNames);
        assertNotNull(agent);
    }

    @Test
    @SuppressWarnings("deprecation")
    void ragAgenticModeRegistersKnowledgeRetrievalTools() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("rag-agentic")
                        .model(stubModel())
                        .knowledge(stubKnowledge())
                        .ragMode(RAGMode.AGENTIC)
                        .build();
        boolean hasRetrieveTool =
                agent.getToolkit().getToolNames().stream()
                        .anyMatch(n -> n.toLowerCase().contains("retrieve"));
        assertTrue(
                hasRetrieveTool,
                "KnowledgeRetrievalTools should expose a retrieve tool, got: "
                        + agent.getToolkit().getToolNames());
    }

    @Test
    @SuppressWarnings("deprecation")
    void ragGenericModeBuildsWithoutRegisteringTools() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("rag-generic")
                        .model(stubModel())
                        .knowledge(stubKnowledge())
                        .ragMode(RAGMode.GENERIC)
                        .build();
        assertNotNull(agent);
        // GENERIC mode adds a hook; no rag-specific tool is registered.
        Set<String> tools = agent.getToolkit().getToolNames();
        assertFalse(
                tools.stream().anyMatch(n -> n.toLowerCase().contains("retrieve_knowledge")),
                "GENERIC mode must not register agentic retrieval tools: " + tools);
    }

    /**
     * Reflection guard: the hard-deleted 1.x APIs must stay gone. This test will fail loudly if
     * anyone re-adds {@code memory(...)} / {@code statePersistence(...)} to the builder or
     * {@code saveTo} / {@code loadFrom} / {@code loadIfExists} / {@code getMemory} /
     * {@code setMemory} to {@link ReActAgent}.
     */
    @Test
    void hardDeletedApisRemainAbsent() {
        Class<?> builder = ReActAgent.Builder.class;
        Set<String> goneBuilderSetters = Set.of("memory", "statePersistence");
        assertTrue(
                Arrays.stream(builder.getDeclaredMethods())
                        .noneMatch(m -> goneBuilderSetters.contains(m.getName())),
                "Hard-deleted 1.x Builder setters must not be re-introduced: "
                        + Arrays.stream(builder.getDeclaredMethods())
                                .map(Method::getName)
                                .filter(goneBuilderSetters::contains)
                                .toList());

        Class<?> agentClass = ReActAgent.class;
        Set<String> goneAgentMethods =
                Set.of("saveTo", "loadFrom", "loadIfExists", "getMemory", "setMemory");
        assertTrue(
                Arrays.stream(agentClass.getDeclaredMethods())
                        .noneMatch(m -> goneAgentMethods.contains(m.getName())),
                "Hard-deleted 1.x ReActAgent methods must not be re-introduced.");
    }
}
