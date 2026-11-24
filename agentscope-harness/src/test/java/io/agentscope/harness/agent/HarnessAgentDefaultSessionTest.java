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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

@HarnessQuiescence
@SuppressWarnings("deprecation")
class HarnessAgentDefaultSessionTest {
    private static final String NAME = "assistant";
    private static final String SESSION = "conversation";
    private static final String FACT = "The project codename is amber.";
    private static final String QUESTION = "What is the project codename?";
    private static final String ANSWER = "amber";
    private static final String MISSING = "No project codename in context.";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir Path workspace;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void clearsConfiguredDefaultConversation(boolean journalEnabled, boolean streaming) {
        try (HarnessAgent agent = newAgent(journalEnabled, SESSION)) {
            RuntimeContext context =
                    RuntimeContext.builder()
                            .put("requestLabel", "conversation-test")
                            .put(Locale.class, Locale.ENGLISH)
                            .build();
            assertEquals(ANSWER, invoke(agent, FACT, context, streaming).getTextContent());

            agent.clearContext(context);

            assertEquals(MISSING, invoke(agent, QUESTION, context, streaming).getTextContent());
            assertNull(context.getSessionId(), "Default resolution must not modify the caller");
            assertEquals("conversation-test", context.get("requestLabel"));
            assertSame(Locale.ENGLISH, context.get(Locale.class));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contextCallContinuesLegacyDefaultConversation(boolean journalEnabled) {
        try (HarnessAgent agent = newAgent(journalEnabled, SESSION)) {
            assertEquals(ANSWER, agent.call(user(FACT)).block(WAIT).getTextContent());

            assertEquals(
                    ANSWER,
                    agent.call(user(QUESTION), RuntimeContext.empty())
                            .block(WAIT)
                            .getTextContent());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void explicitSessionTakesPrecedence(boolean journalEnabled, boolean streaming) {
        try (HarnessAgent agent = newAgent(journalEnabled, SESSION)) {
            RuntimeContext context =
                    RuntimeContext.builder().sessionId("explicit-conversation").build();
            assertEquals("explicit-conversation", agent.threadKey(context).sessionId());
            assertEquals(ANSWER, invoke(agent, FACT, context, streaming).getTextContent());

            agent.clearContext(context);

            assertEquals(MISSING, invoke(agent, QUESTION, context, streaming).getTextContent());
            assertEquals("explicit-conversation", context.getSessionId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfiguredDefaultUsesAgentName(boolean journalEnabled) {
        try (HarnessAgent agent = newAgent(journalEnabled, null)) {
            RuntimeContext context = RuntimeContext.empty();
            assertEquals(NAME, agent.getDefaultSessionId());
            assertEquals(NAME, agent.threadKey(context).sessionId());
            assertEquals(ANSWER, invoke(agent, FACT, context, false).getTextContent());
            assertEquals(ANSWER, invoke(agent, QUESTION, context, true).getTextContent());

            agent.clearContext(context);

            assertEquals(MISSING, invoke(agent, QUESTION, context, true).getTextContent());
        }
    }

    static Stream<Arguments> contextsWithoutSession() {
        return Stream.of(
                Arguments.of("null context", (RuntimeContext) null),
                Arguments.of("empty context", RuntimeContext.empty()),
                Arguments.of("blank session", RuntimeContext.builder().sessionId(" ").build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contextsWithoutSession")
    void resolvesConfiguredDefault(String description, RuntimeContext context) {
        try (HarnessAgent agent = newAgent(true, SESSION)) {
            assertEquals(SESSION, agent.getDefaultSessionId());
            assertEquals(SESSION, agent.threadKey(context).sessionId());
        }
    }

    private HarnessAgent newAgent(boolean journalEnabled, String defaultSessionId) {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(NAME)
                        .model(new HistoryAwareModel())
                        .workspace(workspace)
                        .journalEnabled(journalEnabled)
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .disableSubagents()
                        .disableWebTools()
                        .disableToolsConfig()
                        .enableAgentTracingLog(false);
        if (defaultSessionId != null) {
            builder.defaultSessionId(defaultSessionId);
        }
        return builder.build();
    }

    private static Msg invoke(
            HarnessAgent agent, String input, RuntimeContext context, boolean streaming) {
        if (streaming) {
            return agent.streamEvents(user(input), context)
                    .ofType(AgentResultEvent.class)
                    .map(AgentResultEvent::getResult)
                    .single()
                    .block(WAIT);
        }
        return agent.call(user(input), context).block(WAIT);
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static final class HistoryAwareModel implements Model {
        @Override
        public String getModelName() {
            return "history-aware-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean factVisible =
                    messages.stream()
                            .anyMatch(
                                    message ->
                                            message.getRole() == MsgRole.USER
                                                    && message.getTextContent().contains(FACT));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(factVisible ? ANSWER : MISSING)
                                                    .build()))
                            .finishReason("stop")
                            .build());
        }
    }
}
