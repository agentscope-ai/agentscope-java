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

package io.agentscope.extensions.judge.openaidecisions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI Decisions client for typed classification of text and images.
 *
 * <p>Each subscription performs one transport execution on boundedElastic. Cancellation and timeout
 * interrupt that execution; custom transports must honor interruption. The transport is borrowed
 * from the caller or the shared factory and is never closed by this client. No client-level retries
 * are performed; an injected transport controls its own network behavior.
 */
public final class OpenAIDecisionsClient {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    // Allow rounding of independently serialized probabilities without normalizing their values.
    private static final double TOLERANCE = 0.000001;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpTransport transport;

    private OpenAIDecisionsClient(Builder builder) {
        apiKey = builder.apiKey;
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("apiKey must be a nonblank single-line value");
        }
        String baseUrl = Objects.requireNonNull(builder.baseUrl, "baseUrl").trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid baseUrl");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute http(s) URL without credentials, query or"
                            + " fragment");
        }
        endpoint = baseUrl.endsWith("/decisions") ? baseUrl : baseUrl + "/decisions";
        model = Objects.requireNonNull(builder.model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        timeout = Objects.requireNonNull(builder.timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        transport =
                builder.transport == null ? HttpTransportFactory.getDefault() : builder.transport;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Sends the request on subscription and validates every answer before emitting a result. */
    public Mono<DecisionResult> decide(DecisionRequest request) {
        return Mono.fromCallable(() -> execute(Objects.requireNonNull(request, "request")))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(
                        timeout,
                        Mono.error(
                                () ->
                                        new OpenAIDecisionsException(
                                                "Decisions request timed out", null, true)));
    }

    /** Blocking convenience method; do not call from a reactive event-loop thread. */
    public DecisionResult decideBlocking(DecisionRequest request) {
        return decide(request).block();
    }

    private DecisionResult execute(DecisionRequest request) {
        List<Map<String, Object>> questions = new ArrayList<>();
        request.questions()
                .forEach(
                        (name, question) -> {
                            Map<String, Object> wire = new LinkedHashMap<>();
                            wire.put("name", name);
                            wire.put("instructions", question.instructions());
                            if (question instanceof DecisionQuestion.Predicate) {
                                wire.put("type", "predicate");
                            } else if (question instanceof DecisionQuestion.Choice choice) {
                                wire.put("type", "choice");
                                wire.put("choices", choice.choices());
                            } else if (question instanceof DecisionQuestion.Score score) {
                                wire.put("type", "score");
                                wire.put("levels", score.levels());
                            }
                            questions.add(wire);
                        });
        String body;
        try {
            body =
                    MAPPER.writeValueAsString(
                            Map.of(
                                    "input",
                                    request.input().toWire(),
                                    "model",
                                    request.model() == null ? model : request.model(),
                                    "questions",
                                    questions));
        } catch (JsonProcessingException e) {
            throw invalid("Unable to encode Decisions request");
        }
        HttpResponse response;
        try {
            response =
                    transport.execute(
                            HttpRequest.builder()
                                    .url(endpoint)
                                    .method("POST")
                                    .header("Authorization", "Bearer " + apiKey)
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .body(body)
                                    .build());
        } catch (HttpTransportException e) {
            throw new OpenAIDecisionsException(
                    "Decisions transport failed", e.getStatusCode(), e.isRetryable());
        }
        if (!response.isSuccessful()) {
            int status = response.getStatusCode();
            throw new OpenAIDecisionsException(
                    "Decisions request failed with HTTP " + status,
                    status,
                    status == 429 || status >= 500);
        }
        try {
            JsonNode root = MAPPER.readTree(response.getBody());
            if (root == null || !root.isObject()) {
                throw invalid("Decisions response must be an object");
            }
            String responseModel = text(root, "model");
            JsonNode answers = root.get("answers");
            if (answers == null
                    || !answers.isArray()
                    || answers.size() != request.questions().size()) {
                throw invalid("Decisions response must answer every question");
            }
            Map<String, DecisionAnswer> result = new LinkedHashMap<>();
            for (JsonNode answer : answers) {
                String name = text(answer, "name");
                DecisionQuestion question = request.questions().get(name);
                if (question == null || result.containsKey(name)) {
                    throw invalid("Unknown or duplicate answer name");
                }
                result.put(name, parseAnswer(question, answer));
            }
            return new DecisionResult(responseModel, result, usage(root.get("usage")));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid("Unable to parse Decisions response");
        }
    }

    private static DecisionAnswer parseAnswer(DecisionQuestion question, JsonNode answer) {
        String type = text(answer, "type");
        if (type.equals("refusal")) {
            return new DecisionAnswer.Refusal();
        }
        if (question instanceof DecisionQuestion.Predicate && type.equals("predicate")) {
            return new DecisionAnswer.Predicate(probability(answer, "probability"));
        }
        if (question instanceof DecisionQuestion.Choice choice && type.equals("choice")) {
            return parseChoice(choice, answer);
        }
        if (question instanceof DecisionQuestion.Score score && type.equals("score")) {
            return parseScore(score, answer);
        }
        throw invalid("Answer type does not match question type");
    }

    private static DecisionAnswer.Choice parseChoice(
            DecisionQuestion.Choice question, JsonNode answer) {
        var expected = new HashSet<Object>();
        question.choices().forEach(option -> expected.add(option.value()));
        Object selected = choiceValue(answer.get("choice"));
        JsonNode distribution = distribution(answer, expected.size());
        List<DecisionAnswer.ChoiceProbability> probabilities = new ArrayList<>();
        var byValue = new HashMap<Object, Double>();
        double sum = 0;
        for (JsonNode entry : distribution) {
            Object value = choiceValue(entry.get("value"));
            double p = probability(entry, "probability");
            if (!expected.contains(value) || byValue.put(value, p) != null) {
                throw invalid("Unknown or duplicate choice probability");
            }
            sum += p;
            probabilities.add(new DecisionAnswer.ChoiceProbability(value, p));
        }
        validateSum(sum, expected.size());
        if (!byValue.containsKey(selected)) {
            throw invalid("Selected choice must be one of the supplied options");
        }
        return new DecisionAnswer.Choice(
                selected, probability(answer, "confidence"), probabilities);
    }

    private static DecisionAnswer.Score parseScore(
            DecisionQuestion.Score question, JsonNode answer) {
        JsonNode distribution = distribution(answer, question.levels().size());
        var seen = new HashSet<Integer>();
        List<DecisionAnswer.ScoreProbability> probabilities = new ArrayList<>();
        double sum = 0;
        double expectation = 0;
        for (JsonNode entry : distribution) {
            JsonNode value = entry.get("value");
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid("Score level index must be an integer");
            }
            int index = value.intValue();
            String label = text(entry, "label");
            if (index < 0
                    || index >= question.levels().size()
                    || !seen.add(index)
                    || !question.levels().get(index).label().equals(label)) {
                throw invalid("Unknown, duplicate or mismatched score level");
            }
            double p = probability(entry, "probability");
            sum += p;
            expectation += index * p;
            probabilities.add(new DecisionAnswer.ScoreProbability(index, label, p));
        }
        validateSum(sum, question.levels().size());
        double score = number(answer, "score");
        if (score < 0
                || score > question.levels().size() - 1
                || Math.abs(score - expectation) > TOLERANCE * question.levels().size()) {
            throw invalid("Score must match the probability-weighted level indices");
        }
        return new DecisionAnswer.Score(score, probability(answer, "confidence"), probabilities);
    }

    private static JsonNode distribution(JsonNode answer, int size) {
        JsonNode distribution = answer.get("probabilities");
        if (distribution == null || !distribution.isArray() || distribution.size() != size) {
            throw invalid("Probability distribution must contain every option or level");
        }
        return distribution;
    }

    private static void validateSum(double sum, int size) {
        if (Math.abs(sum - 1) > TOLERANCE * size) {
            throw invalid("Probabilities must sum to one");
        }
    }

    private static Object choiceValue(JsonNode node) {
        if (node != null && node.isTextual()) {
            return node.textValue();
        }
        if (node != null && node.isBoolean()) {
            return node.booleanValue();
        }
        throw invalid("Choice value must be a string or boolean");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("Missing or invalid text field: " + field);
        }
        return value.textValue();
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw invalid("Missing or invalid numeric field: " + field);
        }
        return value.doubleValue();
    }

    private static double probability(JsonNode node, String field) {
        double value = number(node, field);
        if (value < 0 || value > 1) {
            throw invalid("Probability or confidence outside [0, 1]");
        }
        return value;
    }

    private static DecisionUsage usage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid("usage must be an object");
        }
        return new DecisionUsage(
                count(node, "input_tokens"),
                count(node, "output_tokens"),
                count(node, "total_tokens"));
    }

    private static Long count(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid("Token usage must be a nonnegative integer");
        }
        return value.longValue();
    }

    private static OpenAIDecisionsException invalid(String message) {
        return new OpenAIDecisionsException(message, null, false);
    }

    public static final class Builder {
        private String apiKey = System.getenv("OPENAI_API_KEY");
        private String baseUrl = DEFAULT_BASE_URL;
        private String model = DEFAULT_MODEL;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpTransport transport;

        private Builder() {}

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** API base URL including the version prefix, or the complete /decisions URL. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public OpenAIDecisionsClient build() {
            return new OpenAIDecisionsClient(this);
        }
    }
}
