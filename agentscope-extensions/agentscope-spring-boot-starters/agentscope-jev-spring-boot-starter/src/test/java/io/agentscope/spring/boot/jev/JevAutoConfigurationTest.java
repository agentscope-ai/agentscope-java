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
package io.agentscope.spring.boot.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class JevAutoConfigurationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JevAutoConfiguration.class));

    @Test
    void shouldCreateJevClientWithProperties() {
        RecordingTransport transport = new RecordingTransport();
        contextRunner
                .withPropertyValues(
                        "agentscope.jev.api-key=test-key",
                        "agentscope.jev.base-url=http://typesafe.test",
                        "agentscope.jev.model=jev-test",
                        "agentscope.jev.timeout=5s",
                        "agentscope.jev.retry.max-retries=2",
                        "agentscope.jev.retry.initial-backoff=500ms")
                .withBean(
                        "transportCustomizer",
                        JevClientBuilderCustomizer.class,
                        () -> builder -> builder.transport(transport))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(JevClient.class);
                            JevClient client = context.getBean(JevClient.class);
                            client.systemOneBlocking(request());

                            HttpRequest httpRequest = transport.request.get();
                            assertThat(httpRequest.getUrl())
                                    .isEqualTo("http://typesafe.test/v1/systemone");
                            assertThat(httpRequest.getHeaders().get("Authorization"))
                                    .isEqualTo("Bearer test-key");
                            JsonNode body = MAPPER.readTree(httpRequest.getBody());
                            assertThat(body.get("model").asText()).isEqualTo("jev-test");
                        });
    }

    @Test
    void shouldNotCreateJevClientWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.jev.enabled=false", "agentscope.jev.api-key=test-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(JevClient.class);
                        });
    }

    @Test
    void shouldBackOffWhenUserDefinesJevClientBean() {
        JevClient custom =
                JevClient.builder()
                        .apiKey("custom-key")
                        .baseUrl("http://custom.test")
                        .model("custom-model")
                        .transport(new RecordingTransport())
                        .build();
        contextRunner
                .withPropertyValues("agentscope.jev.api-key=test-key")
                .withBean("customJevClient", JevClient.class, () -> custom)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(JevClient.class);
                            assertThat(context.getBean(JevClient.class)).isSameAs(custom);
                        });
    }

    @Test
    void shouldApplyJevClientBuilderCustomizer() {
        RecordingTransport transport = new RecordingTransport();
        contextRunner
                .withPropertyValues(
                        "agentscope.jev.api-key=test-key",
                        "agentscope.jev.base-url=http://typesafe.test")
                .withBean(
                        "transportCustomizer",
                        JevClientBuilderCustomizer.class,
                        () -> builder -> builder.transport(transport))
                .withBean(
                        "customizer",
                        JevClientBuilderCustomizer.class,
                        () -> builder -> builder.model("customized-model"))
                .withBean("recordingTransport", RecordingTransport.class, () -> transport)
                .run(
                        context -> {
                            JevClient client = context.getBean(JevClient.class);
                            client.systemOneBlocking(request());

                            JsonNode body = MAPPER.readTree(transport.request.get().getBody());
                            assertThat(body.get("model").asText()).isEqualTo("customized-model");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnJevClientBuilderCustomizer() {
        JevClient.Builder builder =
                JevClient.builder()
                        .apiKey("test-key")
                        .baseUrl("http://typesafe.test")
                        .transport(new RecordingTransport());
        AtomicReference<JevClient.Builder> capturedBuilder = new AtomicReference<>();
        JevClientBuilderCustomizer customizer = capturedBuilder::set;

        customizer.accept(builder);

        assertThat(capturedBuilder.get()).isSameAs(builder);
    }

    private static SystemOneRequest request() {
        return SystemOneRequest.builder()
                .state("Which team should handle this?")
                .question(
                        "team",
                        new ChoiceQuestion(
                                "Which team should handle this?",
                                Map.of("billing", "Payments", "technical", "Bugs")))
                .build();
    }

    private static final class RecordingTransport implements HttpTransport {

        private final AtomicReference<HttpRequest> request = new AtomicReference<>();

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.request.set(request);
            return HttpResponse.builder()
                    .statusCode(200)
                    .body(
                            """
                            {
                              "model": "jev-1.13.0",
                              "answers": {
                                "team": {
                                  "type": "choice",
                                  "choice": "technical",
                                  "probabilities": {
                                    "billing": 0.2,
                                    "technical": 0.8
                                  },
                                  "confidence": 0.77
                                }
                              },
                              "usage": {
                                "input_tokens": 1,
                                "output_tokens": 1
                              }
                            }
                            """)
                    .build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return Flux.empty();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
