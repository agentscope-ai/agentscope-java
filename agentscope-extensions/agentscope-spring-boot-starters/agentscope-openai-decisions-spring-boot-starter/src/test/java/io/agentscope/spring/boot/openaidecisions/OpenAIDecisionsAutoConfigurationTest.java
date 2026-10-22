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
package io.agentscope.spring.boot.openaidecisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonMissing;
import com.openai.core.RequestOptions;
import com.openai.models.decisions.Decision;
import com.openai.models.decisions.DecisionCreateParams;
import com.openai.services.blocking.DecisionService;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

class OpenAIDecisionsAutoConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(OpenAIDecisionsAutoConfiguration.class));

    @Test
    void bindsPropertiesAndCreatesUsableClient() throws Exception {
        var authorization = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/decisions",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            """
                            {"model":"fixture-model","answers":[{"name":"ok","type":"predicate","probability":1}]}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            runner.withPropertyValues(
                            "agentscope.openai-decisions.api-key=test-key",
                            "agentscope.openai-decisions.base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1",
                            "agentscope.openai-decisions.model=test-model",
                            "agentscope.openai-decisions.timeout=15s")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(OpenAIDecisionsClient.class);
                                var properties = context.getBean(OpenAIDecisionsProperties.class);
                                assertThat(properties.getTimeout())
                                        .isEqualTo(Duration.ofSeconds(15));
                                assertThat(properties.isEnabled()).isTrue();
                                context.getBean(OpenAIDecisionsClient.class)
                                        .decideBlocking(request());
                                assertThat(authorization.get()).isEqualTo("Bearer test-key");
                                assertThat(
                                                new ObjectMapper()
                                                        .readTree(body.get())
                                                        .get("model")
                                                        .textValue())
                                        .isEqualTo("test-model");
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledConfigurationDoesNotRequireCredentials() {
        runner.withPropertyValues("agentscope.openai-decisions.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenAIDecisionsClient.class));
    }

    @Test
    void backsOffForUserSuppliedClient() {
        var sdk = sdkClient();
        var custom = OpenAIDecisionsClient.builder().client(sdk).build();
        runner.withBean(OpenAIDecisionsClient.class, () -> custom)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(OpenAIDecisionsClient.class);
                            assertThat(context.getBean(OpenAIDecisionsClient.class))
                                    .isSameAs(custom);
                        });
        verify(sdk, never()).close();
    }

    @Test
    void orderedCustomizersOverrideProperties() {
        var sdk = sdkClient();
        runner.withPropertyValues("agentscope.openai-decisions.model=property-model")
                .withBean(
                        "sdk",
                        OpenAIDecisionsClientBuilderCustomizer.class,
                        () -> b -> b.client(sdk))
                .withBean(
                        "second",
                        OpenAIDecisionsClientBuilderCustomizer.class,
                        () -> new ModelCustomizer(2, "second-model"))
                .withBean(
                        "first",
                        OpenAIDecisionsClientBuilderCustomizer.class,
                        () -> new ModelCustomizer(1, "first-model"))
                .run(
                        context -> {
                            context.getBean(OpenAIDecisionsClient.class).decideBlocking(request());
                            var captured = ArgumentCaptor.forClass(DecisionCreateParams.class);
                            verify(sdk.decisions())
                                    .create(captured.capture(), any(RequestOptions.class));
                            assertThat(captured.getValue().model()).isEqualTo("second-model");
                        });
        verify(sdk, never()).close();
    }

    @Test
    void rejectsInvalidTimeout() {
        runner.withPropertyValues(
                        "agentscope.openai-decisions.api-key=test-key",
                        "agentscope.openai-decisions.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customizerAcceptDelegates() {
        var builder = OpenAIDecisionsClient.builder().client(sdkClient());
        var captured = new AtomicReference<OpenAIDecisionsClient.Builder>();
        OpenAIDecisionsClientBuilderCustomizer customizer = captured::set;
        customizer.accept(builder);
        assertThat(captured.get()).isSameAs(builder);
    }

    private static OpenAIClient sdkClient() {
        var client = mock(OpenAIClient.class);
        var service = mock(DecisionService.class);
        when(client.decisions()).thenReturn(service);
        when(service.create(any(DecisionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(
                        Decision.builder()
                                .usage(JsonMissing.of())
                                .model("fixture-model")
                                .answers(
                                        List.of(
                                                Decision.Answer.ofPredicate(
                                                        Decision.Answer.Predicate.builder()
                                                                .name("ok")
                                                                .probability(1)
                                                                .build())))
                                .build());
        return client;
    }

    private static DecisionRequest request() {
        return DecisionRequest.builder()
                .input("test")
                .question("ok", new DecisionQuestion.Predicate("Is it ok?"))
                .build();
    }

    private record ModelCustomizer(int order, String model)
            implements OpenAIDecisionsClientBuilderCustomizer, Ordered {
        @Override
        public void customize(OpenAIDecisionsClient.Builder builder) {
            builder.model(model);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
