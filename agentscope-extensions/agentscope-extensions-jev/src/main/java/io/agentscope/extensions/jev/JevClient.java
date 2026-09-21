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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Java client for TypeSafe's System One endpoint.
 *
 * <p>Jev is not a chat model. This client intentionally does not implement AgentScope's
 * {@code Model} contract; use it when application code needs a fast, typed, calibrated decision.
 */
public final class JevClient {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String SYSTEM_ONE_ENDPOINT = "/v1/systemone";
    private static final double PROBABILITY_SUM_TOLERANCE = 0.000001;

    static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpTransport transport;
    private final JevRetryPolicy retryPolicy;
    private final Duration timeout;

    private JevClient(Builder builder, HttpTransport transport) {
        this.apiKey = builder.apiKey;
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.model = builder.model;
        this.transport = transport;
        this.retryPolicy = builder.retryPolicy;
        this.timeout = builder.timeout;
        validateClient();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Sends a System One request and returns the validated result. */
    public Mono<SystemOneResult> systemOne(SystemOneRequest request) {
        return Mono.defer(
                () -> {
                    Objects.requireNonNull(request, "request");
                    validateRequest(request);
                    return Mono.fromCallable(() -> execute(request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(timeout, Mono.error(this::requestTimeout))
                            .retryWhen(retrySpec());
                });
    }

    /** Blocking convenience wrapper for {@link #systemOne(SystemOneRequest)}. */
    public SystemOneResult systemOneBlocking(SystemOneRequest request) {
        return systemOne(request).block();
    }

    private SystemOneResult execute(SystemOneRequest request) {
        String effectiveModel =
                request.model() == null || request.model().isBlank() ? model : request.model();

        SystemOneRequest payload =
                new SystemOneRequest(request.state(), effectiveModel, request.questions());

        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new JevException("Failed to serialize System One request", e);
        }

        HttpRequest httpRequest =
                HttpRequest.builder()
                        .url(systemOneUrl())
                        .method("POST")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();

        HttpResponse response;
        try {
            response = transport.execute(httpRequest);
        } catch (HttpTransportException e) {
            throw new JevException(
                    "System One transport failed",
                    e,
                    e.getStatusCode(),
                    e.getResponseBody(),
                    e.isRetryable());
        }

        if (!response.isSuccessful()) {
            throw httpException(response);
        }

        SystemOneResult result;
        try {
            result = MAPPER.readValue(response.getBody(), SystemOneResult.class);
        } catch (JsonProcessingException e) {
            throw new JevException(
                    "Failed to parse System One response",
                    response.getStatusCode(),
                    response.getBody(),
                    false);
        }

        validateResult(request, result);
        return result;
    }

    private Retry retrySpec() {
        return Retry.backoff(retryPolicy.maxRetries(), retryPolicy.initialBackoff())
                .maxBackoff(retryPolicy.initialBackoff().multipliedBy(8))
                .jitter(0.2)
                .filter(error -> error instanceof JevException exception && exception.isRetryable())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private String systemOneUrl() {
        if (baseUrl.endsWith(SYSTEM_ONE_ENDPOINT)) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/systemone";
        }
        return baseUrl + SYSTEM_ONE_ENDPOINT;
    }

    private static JevException httpException(HttpResponse response) {
        String body = response.getBody();
        int status = response.getStatusCode();
        String message = errorMessage(body);
        if (message == null || message.isBlank()) {
            message = "System One request failed with HTTP " + status;
        }
        boolean retryable = status == 429 || status == 529 || status >= 500;
        return new JevException(message, status, body, retryable);
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String message = textAtPath(root, "message");
            if (message == null) {
                message = textAtPath(root, "error");
            }
            if (message == null) {
                message = textAtPath(root, "detail");
            }
            return message;
        } catch (JsonProcessingException e) {
            return body;
        }
    }

    private JevException requestTimeout() {
        String message = "System One request timed out after " + timeout.toMillis() + "ms";
        return new JevException(message, new TimeoutException(message), null, null, true);
    }

    private static String textAtPath(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        JsonNode nested = root.get("error");
        if (nested != null && nested.isObject()) {
            JsonNode nestedMessage = nested.get(fieldName);
            if (nestedMessage != null && nestedMessage.isTextual()) {
                return nestedMessage.asText();
            }
        }
        return null;
    }

    private void validateClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be a valid URI", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static void validateRequest(SystemOneRequest request) {
        if (request.state() == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (request.questions() == null || request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
        for (Map.Entry<String, Question> entry : request.questions().entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("question ids must not be blank");
            }
            validateQuestion(id, entry.getValue());
        }
    }

    private static void validateQuestion(String id, Question question) {
        if (question == null) {
            throw new IllegalArgumentException("question '" + id + "' must not be null");
        }
        if (question instanceof NoulQuestion noul) {
            requireInstructions(id, noul.instructions());
        } else if (question instanceof ChoiceQuestion choice) {
            requireInstructions(id, choice.instructions());
            if (choice.criteria() == null) {
                throw new IllegalArgumentException(
                        "question '" + id + "' criteria must not be null");
            }
            if (choice.criteria().size() < 2 || choice.criteria().size() > 255) {
                throw new IllegalArgumentException(
                        "question '" + id + "' criteria must contain 2 to 255 options");
            }
        } else if (question instanceof ScoreQuestion score) {
            requireInstructions(id, score.instructions());
            if (score.criteria() == null) {
                throw new IllegalArgumentException(
                        "question '" + id + "' criteria must not be null");
            }
            if (score.criteria().size() < 2 || score.criteria().size() > 10) {
                throw new IllegalArgumentException(
                        "question '" + id + "' criteria must contain 2 to 10 levels");
            }
        } else {
            throw new IllegalArgumentException("question '" + id + "' has unsupported type");
        }
    }

    private static void requireInstructions(String id, Object instructions) {
        if (instructions == null) {
            throw new IllegalArgumentException(
                    "question '" + id + "' instructions must not be null");
        }
    }

    private static void validateResult(SystemOneRequest request, SystemOneResult result) {
        if (result.model() == null || result.model().isBlank()) {
            throw new JevException("System One response model must not be blank");
        }
        if (result.answers() == null) {
            throw new JevException("System One response answers must not be null");
        }
        if (result.usage() == null) {
            throw new JevException("System One response usage must not be null");
        }
        if (!result.answers().keySet().equals(request.questions().keySet())) {
            throw new JevException("System One response answer keys do not match request keys");
        }
        for (Map.Entry<String, Question> entry : request.questions().entrySet()) {
            String id = entry.getKey();
            Answer answer = result.answers().get(id);
            validateAnswer(id, entry.getValue(), answer);
        }
    }

    private static void validateAnswer(String id, Question question, Answer answer) {
        if (answer == null) {
            throw new JevException("System One response missing answer for question '" + id + "'");
        }
        if (question instanceof NoulQuestion && !(answer instanceof NoulAnswer)) {
            throw mismatch(id, "noul");
        }
        if (question instanceof ChoiceQuestion && !(answer instanceof ChoiceAnswer)) {
            throw mismatch(id, "choice");
        }
        if (question instanceof ScoreQuestion && !(answer instanceof ScoreAnswer)) {
            throw mismatch(id, "score");
        }

        if (answer instanceof NoulAnswer noul) {
            requireProbability(id, noul.noul());
        } else if (answer instanceof ChoiceAnswer choice) {
            validateChoiceAnswer(id, (ChoiceQuestion) question, choice);
        } else if (answer instanceof ScoreAnswer score) {
            validateScoreAnswer(id, (ScoreQuestion) question, score);
        }
    }

    private static JevException mismatch(String id, String expectedType) {
        return new JevException(
                "System One answer for question '" + id + "' must have type " + expectedType);
    }

    private static void validateChoiceAnswer(
            String id, ChoiceQuestion question, ChoiceAnswer answer) {
        if (answer.choice() == null || !question.criteria().containsKey(answer.choice())) {
            throw new JevException(
                    "System One answer for question '" + id + "' selected an unknown option");
        }
        validateProbabilities(id, answer.probabilities(), question.criteria().keySet());
        requireConfidence(id, answer.confidence());
    }

    private static void validateScoreAnswer(String id, ScoreQuestion question, ScoreAnswer answer) {
        if (answer.score() == null
                || !Double.isFinite(answer.score())
                || answer.score() < 0
                || answer.score() > question.criteria().size() - 1) {
            throw new JevException(
                    "System One answer for question '"
                            + id
                            + "' score must be finite and within the rubric range");
        }
        if (answer.legend() == null
                || answer.probabilities() == null
                || !answer.legend().keySet().equals(answer.probabilities().keySet())) {
            throw new JevException(
                    "System One answer for question '"
                            + id
                            + "' legend and probabilities must describe the same levels");
        }
        Set<String> expectedLevels = levelKeys(question.criteria().size());
        if (!answer.probabilities().keySet().equals(expectedLevels)) {
            throw new JevException(
                    "System One answer for question '"
                            + id
                            + "' probabilities must cover every rubric level");
        }
        validateProbabilities(id, answer.probabilities(), expectedLevels);
        requireConfidence(id, answer.confidence());
    }

    private static void validateProbabilities(
            String id, Map<String, Double> probabilities, Set<String> expectedKeys) {
        if (probabilities == null || !probabilities.keySet().equals(expectedKeys)) {
            throw new JevException(
                    "System One answer for question '"
                            + id
                            + "' probabilities must match the question criteria");
        }
        double sum = 0;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            requireProbability(id, entry.getValue());
            sum += entry.getValue();
        }
        if (Math.abs(sum - 1) > PROBABILITY_SUM_TOLERANCE) {
            throw new JevException(
                    "System One answer for question '" + id + "' probabilities must sum to 1");
        }
    }

    private static void requireProbability(String id, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new JevException(
                    "System One answer for question '" + id + "' contains an invalid probability");
        }
    }

    private static void requireConfidence(String id, Double confidence) {
        if (confidence == null
                || !Double.isFinite(confidence)
                || confidence < 0
                || confidence > 1) {
            throw new JevException(
                    "System One answer for question '" + id + "' contains invalid confidence");
        }
    }

    private static Set<String> levelKeys(int size) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            keys.add(Integer.toString(i));
        }
        return keys;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = Objects.requireNonNull(value, "baseUrl").trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static final class Builder {
        private String apiKey = defaultApiKey();
        private String baseUrl = DEFAULT_BASE_URL;
        private String model = DEFAULT_MODEL;
        private HttpTransport transport;
        private JevRetryPolicy retryPolicy = JevRetryPolicy.defaults();
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder() {}

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder retryPolicy(JevRetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public JevClient build() {
            HttpTransport effectiveTransport =
                    transport != null ? transport : HttpTransportFactory.getDefault();
            return new JevClient(this, effectiveTransport);
        }

        private static String defaultApiKey() {
            String apiKey = System.getenv("TYPESAFE_API_KEY");
            return apiKey != null ? apiKey : System.getenv("JEV_API_KEY");
        }
    }
}
