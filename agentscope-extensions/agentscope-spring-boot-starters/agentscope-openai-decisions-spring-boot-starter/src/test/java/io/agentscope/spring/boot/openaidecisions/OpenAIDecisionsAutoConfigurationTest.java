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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

class OpenAIDecisionsAutoConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(OpenAIDecisionsAutoConfiguration.class));

    @Test
    void bindsPropertiesAndCreatesUsableClient() {
        var transport = new RecordingTransport();
        runner.withPropertyValues(
                        "agentscope.openai-decisions.api-key=test-key",
                        "agentscope.openai-decisions.base-url=http://decisions.test/v1",
                        "agentscope.openai-decisions.model=test-model",
                        "agentscope.openai-decisions.timeout=2s")
                .withBean(
                        OpenAIDecisionsClientBuilderCustomizer.class,
                        () -> b -> b.transport(transport))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(OpenAIDecisionsClient.class);
                            var properties = context.getBean(OpenAIDecisionsProperties.class);
                            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
                            assertThat(properties.isEnabled()).isTrue();
                            context.getBean(OpenAIDecisionsClient.class).decideBlocking(request());
                            assertThat(transport.request.getUrl())
                                    .isEqualTo("http://decisions.test/v1/decisions");
                            assertThat(transport.request.getHeaders().get("Authorization"))
                                    .isEqualTo("Bearer test-key");
                            assertThat(
                                            new ObjectMapper()
                                                    .readTree(transport.request.getBody())
                                                    .get("model")
                                                    .textValue())
                                    .isEqualTo("test-model");
                        });
    }

    @Test
    void disabledConfigurationDoesNotRequireCredentials() {
        runner.withPropertyValues("agentscope.openai-decisions.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenAIDecisionsClient.class));
    }

    @Test
    void backsOffForUserSuppliedClient() {
        var custom =
                OpenAIDecisionsClient.builder()
                        .apiKey("key")
                        .transport(new RecordingTransport())
                        .build();
        runner.withBean(OpenAIDecisionsClient.class, () -> custom)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(OpenAIDecisionsClient.class);
                            assertThat(context.getBean(OpenAIDecisionsClient.class))
                                    .isSameAs(custom);
                        });
    }

    @Test
    void orderedCustomizersOverrideProperties() {
        var transport = new RecordingTransport();
        runner.withPropertyValues(
                        "agentscope.openai-decisions.api-key=test-key",
                        "agentscope.openai-decisions.model=property-model")
                .withBean(
                        "transport",
                        OpenAIDecisionsClientBuilderCustomizer.class,
                        () -> b -> b.transport(transport))
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
                            assertThat(
                                            new ObjectMapper()
                                                    .readTree(transport.request.getBody())
                                                    .get("model")
                                                    .textValue())
                                    .isEqualTo("second-model");
                        });
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
        var builder =
                OpenAIDecisionsClient.builder().apiKey("key").transport(new RecordingTransport());
        var captured = new AtomicReference<OpenAIDecisionsClient.Builder>();
        OpenAIDecisionsClientBuilderCustomizer customizer = captured::set;
        customizer.accept(builder);
        assertThat(captured.get()).isSameAs(builder);
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

    private static final class RecordingTransport implements HttpTransport {
        HttpRequest request;

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.request = request;
            return HttpResponse.builder()
                    .statusCode(200)
                    .body(
                            """
                            {"model":"fixture-model","answers":[{"name":"ok","type":"predicate","probability":1}]}
                            """)
                    .build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return Flux.empty();
        }

        @Override
        public void close() {}
    }
}
