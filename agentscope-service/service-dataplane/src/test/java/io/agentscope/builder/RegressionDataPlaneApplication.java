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
package io.agentscope.builder;

import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/** Isolated HTTP regression launcher: real persistence, deterministic model, no provider API key. */
public final class RegressionDataPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {DataApp.class, FixtureConfiguration.class}, args);
    }

    @Configuration
    static class FixtureConfiguration {
        @Bean
        static BeanPostProcessor regressionExternalTool() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof HarnessAgentBuildService)) return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(
                            (MethodInterceptor)
                                    invocation -> {
                                        Object result = invocation.proceed();
                                        if (result instanceof HarnessAgent agent
                                                && invocation
                                                        .getMethod()
                                                        .getName()
                                                        .equals("getOrBuildAgent")) {
                                            var toolkit = agent.getToolkit();
                                            synchronized (toolkit) {
                                                if (toolkit.getTool("ask_user") == null) {
                                                    toolkit.registerSchema(
                                                            ToolSchema.builder()
                                                                    .name("ask_user")
                                                                    .description(
                                                                            "Request an external"
                                                                                    + " regression"
                                                                                    + " answer")
                                                                    .parameters(
                                                                            Map.of(
                                                                                    "type",
                                                                                    "object",
                                                                                    "properties",
                                                                                    Map.of(
                                                                                            "question",
                                                                                            Map.of(
                                                                                                    "type",
                                                                                                    "string")),
                                                                                    "required",
                                                                                    List.of(
                                                                                            "question")))
                                                                    .build());
                                                }
                                            }
                                        }
                                        return result;
                                    });
                    return proxy.getProxy();
                }
            };
        }

        @Bean
        Model regressionModel() {
            return new Model() {
                @Override
                public String getModelName() {
                    return "regression-offline";
                }

                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    Msg last = messages.get(messages.size() - 1);
                    String prompt =
                            messages.stream()
                                    .filter(message -> message.getRole() == MsgRole.USER)
                                    .reduce((first, second) -> second)
                                    .map(Msg::getTextContent)
                                    .orElse("");
                    String completionTool =
                            tools == null
                                    ? null
                                    : tools.stream()
                                            .map(ToolSchema::getName)
                                            .filter(
                                                    name ->
                                                            name.endsWith("task.complete")
                                                                    || name.endsWith(
                                                                            "task_complete"))
                                            .findFirst()
                                            .orElse(null);
                    if (completionTool != null
                            && last.getContentBlocks(ToolResultBlock.class).isEmpty()) {
                        Map<String, Object> result =
                                Map.of(
                                        "outcome",
                                        "succeeded",
                                        "summary",
                                        "Regression task completed",
                                        "result",
                                        "Regression reply completed");
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id(UUID.randomUUID().toString())
                                                                .name(completionTool)
                                                                .input(result)
                                                                .content(
                                                                        JsonUtils.getJsonCodec()
                                                                                .toJson(result))
                                                                .build()))
                                        .finishReason("tool_calls")
                                        .build());
                    }
                    if (prompt.startsWith("/ask")
                            && last.getContentBlocks(ToolResultBlock.class).isEmpty()) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id(UUID.randomUUID().toString())
                                                                .name("ask_user")
                                                                .input(
                                                                        Map.of(
                                                                                "question",
                                                                                "Choose a"
                                                                                    + " regression"
                                                                                    + " value"))
                                                                .content(
                                                                        "{\"question\":\"Choose a"
                                                                                + " regression"
                                                                                + " value\"}")
                                                                .build()))
                                        .finishReason("tool_calls")
                                        .build());
                    }
                    String reply =
                            last.getContentBlocks(ToolResultBlock.class).isEmpty()
                                    ? "Regression reply completed"
                                    : "Regression resumed after tool result";
                    String responseId = UUID.randomUUID().toString();
                    return Flux.fromIterable(
                                    reply.codePoints()
                                            .mapToObj(code -> new String(Character.toChars(code)))
                                            .toList())
                            .delayElements(
                                    prompt.startsWith("/slow")
                                            ? Duration.ofMillis(100)
                                            : Duration.ofMillis(3))
                            .map(
                                    chunk ->
                                            ChatResponse.builder()
                                                    .id(responseId)
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text(chunk)
                                                                            .build()))
                                                    .build());
                }
            };
        }
    }
}
