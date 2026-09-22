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

package io.agentscope.extensions.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

class JevClientTest {

    private final HttpTransport transport = mock(HttpTransport.class);

    private JevClient client() {
        return JevClient.builder()
                .apiKey("test-key")
                .baseUrl("http://typesafe.test")
                .model("jev-latest")
                .transport(transport)
                .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(1)))
                .build();
    }

    private SystemOneRequest request() {
        return SystemOneRequest.builder()
                .state("My payouts are failing")
                .question(
                        "team",
                        new ChoiceQuestion(
                                "Which team should handle this?",
                                Map.of("billing", "Payments", "technical", "Bugs")))
                .build();
    }

    private static HttpResponse response(int status, String body) {
        return HttpResponse.builder().statusCode(status).body(body).build();
    }

    private static String successBody() {
        return """
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
            "input_tokens": 100,
            "output_tokens": 20
          }
        }
        """;
    }

    @Test
    void sendsValidatedHttpRequestAndParsesResponse() throws Exception {
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, successBody()));

        SystemOneResult result = client().systemOneBlocking(request());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        HttpRequest httpRequest = captor.getValue();
        assertEquals("http://typesafe.test/v1/systemone", httpRequest.getUrl());
        assertEquals("POST", httpRequest.getMethod());
        assertEquals("Bearer test-key", httpRequest.getHeaders().get("Authorization"));
        assertEquals("application/json", httpRequest.getHeaders().get("Content-Type"));

        JsonNode body = JevClient.MAPPER.readTree(httpRequest.getBody());
        assertEquals("My payouts are failing", body.get("state").asText());
        assertEquals("jev-latest", body.get("model").asText());
        assertEquals("choice", body.get("questions").get("team").get("type").asText());

        assertEquals("jev-1.13.0", result.model());
        ChoiceAnswer answer = (ChoiceAnswer) result.answers().get("team");
        assertEquals("technical", answer.choice());
        assertEquals(0.8, answer.probabilities().get("technical"));
        assertEquals(100, result.usage().inputTokens());
    }

    @Test
    void supportsBaseUrlWithVersionAndFullEndpoint() {
        JevClient versioned =
                JevClient.builder()
                        .apiKey("test-key")
                        .baseUrl("http://typesafe.test/v1/")
                        .transport(transport)
                        .build();
        assertTrue(versioned != null);

        JevClient full =
                JevClient.builder()
                        .apiKey("test-key")
                        .baseUrl("http://typesafe.test/v1/systemone")
                        .transport(transport)
                        .build();
        assertTrue(full != null);
    }

    @Test
    void mapsNonRetryableHttpError() {
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(response(401, "{\"message\":\"invalid key\"}"));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertEquals("invalid key", exception.getMessage());
        assertEquals(401, exception.getStatusCode());
        assertTrue(!exception.isRetryable());
        verify(transport).execute(any(HttpRequest.class));
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(response(429, "{\"message\":\"slow down\"}"))
                .thenReturn(response(200, successBody()));

        SystemOneResult result = client().systemOneBlocking(request());

        assertTrue(result.answers().containsKey("team"));
        verify(transport, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void retriesOverloadedThenFailsAfterMaxRetries() {
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(response(529, "{\"message\":\"overloaded\"}"));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertTrue(exception.getMessage().contains("overloaded"));
        verify(transport, times(3)).execute(any(HttpRequest.class));
    }

    @Test
    void rejectsResponseWithUnknownAnswerKey() {
        String body =
                """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "other": {
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
                    "input_tokens": 100,
                    "output_tokens": 20
                  }
                }
                """;
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, body));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertTrue(exception.getMessage().contains("answer keys do not match"));
    }

    @Test
    void rejectsInvalidProbability() {
        String body =
                """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "team": {
                      "type": "choice",
                      "choice": "technical",
                      "probabilities": {
                        "billing": 0.8,
                        "technical": 0.8
                      },
                      "confidence": 0.77
                    }
                  },
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 20
                  }
                }
                """;
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, body));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertTrue(exception.getMessage().contains("probabilities must sum to 1"));
    }

    @Test
    void rejectsAnswerQuestionTypeMismatch() {
        String body =
                """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "team": {
                      "type": "noul",
                      "noul": 1.0
                    }
                  },
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 20
                  }
                }
                """;
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, body));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertTrue(exception.getMessage().contains("must have type choice"));
    }

    @Test
    void validatesScoreLegendAndLevels() {
        SystemOneRequest request =
                SystemOneRequest.builder()
                        .state("message")
                        .question(
                                "score",
                                new ScoreQuestion("How urgent is this?", List.of("Low", "High")))
                        .build();
        String body =
                """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "score": {
                      "type": "score",
                      "score": 1.0,
                      "legend": {
                        "0": "Low"
                      },
                      "probabilities": {
                        "0": 1.0
                      },
                      "confidence": 1.0
                    }
                  },
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 20
                  }
                }
                """;
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, body));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request));
        assertTrue(exception.getMessage().contains("probabilities must cover every rubric level"));
    }

    @Test
    void rejectsNullResponseBody() {
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, "null"));

        JevException exception =
                assertThrows(JevException.class, () -> client().systemOneBlocking(request()));
        assertTrue(exception.getMessage().contains("must not be null"));
    }

    @Test
    void scalesProbabilitySumToleranceWithOptionCount() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (int i = 0; i < 255; i++) {
            criteria.put("option_" + i, "Option " + i);
        }
        SystemOneRequest request =
                SystemOneRequest.builder()
                        .state("Pick one")
                        .question("choice", new ChoiceQuestion("Pick one", criteria))
                        .build();

        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < 255; i++) {
            double probability = 1.0 / 255;
            if (i == 0) {
                probability += 0.00001;
            }
            probabilities.put("option_" + i, probability);
        }
        String body =
                JevClient.MAPPER
                        .valueToTree(
                                Map.of(
                                        "model", "jev-1.13.0",
                                        "answers",
                                                Map.of(
                                                        "choice",
                                                        Map.of(
                                                                "type",
                                                                "choice",
                                                                "choice",
                                                                "option_0",
                                                                "probabilities",
                                                                probabilities,
                                                                "confidence",
                                                                0.9)),
                                        "usage", Map.of("input_tokens", 100, "output_tokens", 20)))
                        .toString();
        when(transport.execute(any(HttpRequest.class))).thenReturn(response(200, body));

        SystemOneResult result = client().systemOneBlocking(request);

        assertTrue(result.answers().containsKey("choice"));
    }

    @Test
    void validatesClientAndRequestInput() {
        IllegalArgumentException missingKey =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                JevClient.builder()
                                        .apiKey(" ")
                                        .baseUrl("http://typesafe.test")
                                        .build());
        assertTrue(missingKey.getMessage().contains("apiKey"));

        IllegalArgumentException invalidUrl =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                JevClient.builder()
                                        .apiKey("k")
                                        .baseUrl("not-a-url")
                                        .transport(transport)
                                        .build());
        assertTrue(invalidUrl.getMessage().contains("absolute http(s)"));

        IllegalArgumentException missingState =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client().systemOneBlocking(SystemOneRequest.builder().build()));
        assertTrue(missingState.getMessage().contains("state"));

        IllegalArgumentException invalidChoice =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                client().systemOneBlocking(
                                                SystemOneRequest.builder()
                                                        .state("x")
                                                        .question(
                                                                "one",
                                                                new ChoiceQuestion(
                                                                        "Pick", Map.of("a", 1)))
                                                        .build()));
        assertTrue(invalidChoice.getMessage().contains("2 to 255"));
    }

    @Test
    void blankPrimaryApiKeyFallsBackToJevApiKey() {
        assertEquals("fallback", JevClient.Builder.defaultApiKey(" ", "fallback"));
        assertEquals("primary", JevClient.Builder.defaultApiKey("primary", "fallback"));
    }

    @Test
    void requestValidationFlowsThroughMono() {
        StepVerifier.create(client().systemOne(SystemOneRequest.builder().build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void timesOutSlowTransport() throws Exception {
        when(transport.execute(any(HttpRequest.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(50);
                            return response(200, successBody());
                        });

        JevClient client =
                JevClient.builder()
                        .apiKey("test-key")
                        .baseUrl("http://typesafe.test")
                        .model("jev-latest")
                        .transport(transport)
                        .retryPolicy(new JevRetryPolicy(0, Duration.ofMillis(1)))
                        .timeout(Duration.ofMillis(1))
                        .build();

        StepVerifier.create(client.systemOne(request()))
                .expectErrorSatisfies(error -> assertTrue(error.getMessage().contains("timed out")))
                .verify();
        verify(transport).execute(any(HttpRequest.class));
    }

    @Test
    void resolvesDefaultTransportAtBuildTime() {
        HttpTransport first = mock(HttpTransport.class);
        HttpTransport second = mock(HttpTransport.class);
        HttpTransport original = HttpTransportFactory.getDefault();
        try {
            HttpTransportFactory.setDefault(first);
            JevClient.Builder builder =
                    JevClient.builder()
                            .apiKey("test-key")
                            .baseUrl("http://typesafe.test")
                            .model("jev-latest");
            HttpTransportFactory.setDefault(second);
            when(second.execute(any(HttpRequest.class))).thenReturn(response(200, successBody()));

            builder.build().systemOneBlocking(request());

            verify(second).execute(any(HttpRequest.class));
            verify(first, never()).execute(any(HttpRequest.class));
        } finally {
            HttpTransportFactory.setDefault(original);
            HttpTransportFactory.unregister(first);
            HttpTransportFactory.unregister(second);
        }
    }
}
