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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Regression test for #3829: user middleware registrations in {@code onSystemPrompt} must
 * survive the framework reset performed by {@link ContextConventionsMiddleware}.
 */
class ContextConventionsResetOrderTest {

    @Test
    void userMiddlewareRegistrationsSurviveFrameworkMaterialsReset() {
        List<String> capturedSystemPrompts = new CopyOnWriteArrayList<>();
        MiddlewareBase userMiddleware =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                        WorkspaceContextMaterials.register(
                                ctx,
                                List.of(
                                        ContextItem.instruction(
                                                "my_policy", "my:policy", "USER_POLICY_TEXT")));
                        return Mono.just(currentPrompt != null ? currentPrompt : "");
                    }
                };

        Model captureModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        messages.stream()
                                .filter(msg -> msg.getRole() == MsgRole.SYSTEM)
                                .map(Msg::getTextContent)
                                .forEach(capturedSystemPrompts::add);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }

                    @Override
                    public int getContextWindowSize() {
                        return 100000;
                    }

                    @Override
                    public String getModelName() {
                        return "test-model";
                    }
                };

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("regression-3829")
                        .sysPrompt("Base instructions.")
                        .model(captureModel)
                        .middleware(new ContextConventionsMiddleware())
                        .middleware(userMiddleware)
                        .modelRequestPreparer(
                                new HarnessContextBuilder(ContextPolicy.defaults(), null, null))
                        .build()) {
            agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()))
                    .block();
        }

        assertEquals(1, capturedSystemPrompts.size());
        String systemPrompt = capturedSystemPrompts.get(0);
        assertTrue(
                systemPrompt.contains("Base instructions."),
                "base prompt must be preserved, got: " + systemPrompt);
        assertTrue(
                systemPrompt.contains("harness:context-conventions"),
                "framework conventions must be present, got: " + systemPrompt);
        assertTrue(
                systemPrompt.contains("USER_POLICY_TEXT"),
                "user-registered item must survive the framework reset (#3829), got: "
                        + systemPrompt);
    }
}
