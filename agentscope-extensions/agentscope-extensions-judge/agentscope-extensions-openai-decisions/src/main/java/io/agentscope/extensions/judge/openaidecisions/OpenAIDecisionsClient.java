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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonField;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.decisions.Decision;
import com.openai.models.decisions.DecisionChoiceOption;
import com.openai.models.decisions.DecisionChoiceValue;
import com.openai.models.decisions.DecisionCreateParams;
import com.openai.models.decisions.DecisionInputImage;
import com.openai.models.decisions.DecisionInputMessage;
import com.openai.models.decisions.DecisionInputPart;
import com.openai.models.decisions.DecisionInputText;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI Decisions client backed by the official OpenAI Java SDK.
 *
 * <p>Each subscription performs a blocking SDK call on boundedElastic. Timeout and cancellation
 * interrupt the executing task; the SDK transport determines when in-flight network I/O stops.
 * Clients created by this wrapper have automatic retries disabled and
 * are closed by {@link #close()}. An injected SDK client is borrowed and retains its own retry and
 * resource ownership policies.
 */
public final class OpenAIDecisionsClient implements AutoCloseable {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    // Allow rounding of independently serialized probabilities without normalizing their values.
    private static final double TOLERANCE = 0.000001;
    private final OpenAIClient client;
    private final boolean ownsClient;
    private final String model;
    private final Duration timeout;

    private OpenAIDecisionsClient(Builder builder) {
        model = Objects.requireNonNull(builder.model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        timeout = Objects.requireNonNull(builder.timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        ownsClient = builder.client == null;
        if (ownsClient) {
            String apiKey = builder.apiKey;
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must be a nonblank value");
            }
            client =
                    OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(apiBase(builder.baseUrl))
                            .timeout(timeout)
                            .maxRetries(0)
                            .build();
        } else {
            client = builder.client;
        }
    }

    private static String apiBase(String value) {
        String baseUrl = Objects.requireNonNull(value, "baseUrl").trim();
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
        return baseUrl.endsWith("/decisions")
                ? baseUrl.substring(0, baseUrl.length() - "/decisions".length())
                : baseUrl;
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

    /** Closes the SDK client created by this wrapper; injected SDK clients remain open. */
    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    private DecisionCreateParams params(DecisionRequest request) {
        var params =
                DecisionCreateParams.builder()
                        .model(request.model() == null ? model : request.model());
        if (request.promptCacheKey() != null) {
            params.promptCacheKey(request.promptCacheKey());
        }
        DecisionInput input = request.input();
        if (input.imageUrls().isEmpty()) {
            params.input(input.text());
        } else {
            List<DecisionInputPart> parts = new ArrayList<>();
            if (input.text() != null) {
                parts.add(
                        DecisionInputPart.ofInputText(
                                DecisionInputText.builder().text(input.text()).build()));
            }
            for (String image : input.imageUrls()) {
                parts.add(
                        DecisionInputPart.ofInputImage(
                                DecisionInputImage.builder().imageUrl(image).build()));
            }
            params.inputOfDecisionInputMessages(
                    List.of(DecisionInputMessage.builder().contentOfParts(parts).build()));
        }
        request.questions()
                .forEach(
                        (name, question) -> {
                            if (question instanceof DecisionQuestion.Predicate predicate) {
                                params.addQuestion(
                                        DecisionCreateParams.Question.Predicate.builder()
                                                .name(name)
                                                .instructions(predicate.instructions())
                                                .build());
                            } else if (question instanceof DecisionQuestion.Choice choice) {
                                var sdkQuestion =
                                        DecisionCreateParams.Question.Choice.builder()
                                                .name(name)
                                                .instructions(choice.instructions());
                                for (var option : choice.choices()) {
                                    var sdkOption =
                                            DecisionChoiceOption.builder()
                                                    .value(choiceValue(option.value()));
                                    if (option.description() != null) {
                                        sdkOption.description(option.description());
                                    }
                                    sdkQuestion.addChoice(sdkOption.build());
                                }
                                params.addQuestion(sdkQuestion.build());
                            } else if (question instanceof DecisionQuestion.Score score) {
                                var sdkQuestion =
                                        DecisionCreateParams.Question.Score.builder()
                                                .name(name)
                                                .instructions(score.instructions());
                                for (var level : score.levels()) {
                                    var sdkLevel =
                                            DecisionCreateParams.Question.Score.Level.builder()
                                                    .label(level.label());
                                    if (level.description() != null) {
                                        sdkLevel.description(level.description());
                                    }
                                    sdkQuestion.addLevel(sdkLevel.build());
                                }
                                params.addQuestion(sdkQuestion.build());
                            }
                        });
        return params.build();
    }

    private static DecisionChoiceOption.Value choiceValue(Object value) {
        return value instanceof String string
                ? DecisionChoiceOption.Value.ofString(string)
                : DecisionChoiceOption.Value.ofBool((Boolean) value);
    }

    private DecisionResult execute(DecisionRequest request) {
        try {
            Decision response =
                    client.decisions()
                            .create(
                                    params(request),
                                    RequestOptions.builder().timeout(timeout).build());
            if (response == null) {
                throw invalid("Decisions response must be an object");
            }
            String responseModel = response.model();
            List<Decision.Answer> answers = response.answers();
            if (answers.size() != request.questions().size()) {
                throw invalid("Decisions response must answer every question");
            }
            var result = new LinkedHashMap<String, DecisionAnswer>();
            for (Decision.Answer answer : answers) {
                if (answer == null) {
                    throw invalid("Answer must not be null");
                }
                String name;
                if (answer.isPredicate()) {
                    name =
                            answer.asPredicate()
                                    .name()
                                    .orElseThrow(() -> invalid("Missing answer name"));
                } else if (answer.isChoice()) {
                    name =
                            answer.asChoice()
                                    .name()
                                    .orElseThrow(() -> invalid("Missing answer name"));
                } else if (answer.isScore()) {
                    name =
                            answer.asScore()
                                    .name()
                                    .orElseThrow(() -> invalid("Missing answer name"));
                } else if (answer.isRefusal()) {
                    name =
                            answer.asRefusal()
                                    .name()
                                    .orElseThrow(() -> invalid("Missing answer name"));
                } else {
                    throw invalid("Unknown answer type");
                }
                DecisionQuestion question = request.questions().get(name);
                if (question == null || result.containsKey(name)) {
                    throw invalid("Unknown or duplicate answer name");
                }
                result.put(name, parseAnswer(question, answer));
            }
            Decision.Usage usage = optional(response._usage());
            Decision.Usage.InputTokensDetails inputTokensDetails =
                    usage == null ? null : optional(usage._inputTokensDetails());
            Decision.Usage.OutputTokensDetails outputTokensDetails =
                    usage == null ? null : optional(usage._outputTokensDetails());
            return new DecisionResult(
                    responseModel,
                    result,
                    usage == null
                            ? null
                            : new DecisionUsage(
                                    count(usage._inputTokens()),
                                    count(usage._outputTokens()),
                                    count(usage._totalTokens()),
                                    inputTokensDetails == null
                                            ? null
                                            : count(inputTokensDetails._cachedTokens()),
                                    inputTokensDetails == null
                                            ? null
                                            : count(inputTokensDetails._cacheWriteTokens()),
                                    outputTokensDetails == null
                                            ? null
                                            : count(outputTokensDetails._reasoningTokens())));
        } catch (OpenAIServiceException e) {
            int status = e.statusCode();
            throw new OpenAIDecisionsException(
                    "Decisions request failed with HTTP " + status,
                    status,
                    status == 429 || status >= 500);
        } catch (OpenAIInvalidDataException | IllegalArgumentException e) {
            throw invalid("Unable to parse Decisions response");
        } catch (OpenAIException e) {
            throw new OpenAIDecisionsException(
                    "Decisions SDK request failed",
                    null,
                    e instanceof OpenAIIoException || e instanceof OpenAIRetryableException);
        }
    }

    private static DecisionAnswer parseAnswer(DecisionQuestion question, Decision.Answer answer) {
        if (answer.isRefusal()) {
            return new DecisionAnswer.Refusal();
        }
        if (question instanceof DecisionQuestion.Predicate && answer.isPredicate()) {
            return new DecisionAnswer.Predicate(probability(answer.asPredicate().probability()));
        }
        if (question instanceof DecisionQuestion.Choice choice && answer.isChoice()) {
            return parseChoice(choice, answer.asChoice());
        }
        if (question instanceof DecisionQuestion.Score score && answer.isScore()) {
            return parseScore(score, answer.asScore());
        }
        throw invalid("Answer type does not match question type");
    }

    private static DecisionAnswer.Choice parseChoice(
            DecisionQuestion.Choice question, Decision.Answer.Choice answer) {
        var expected = new HashSet<Object>();
        question.choices().forEach(option -> expected.add(option.value()));
        Object selected = choiceValue(answer.choice());
        if (answer.probabilities().size() != expected.size()) {
            throw invalid("Probability distribution must contain every option");
        }
        List<DecisionAnswer.ChoiceProbability> probabilities = new ArrayList<>();
        var seen = new HashSet<Object>();
        double sum = 0;
        for (var entry : answer.probabilities()) {
            if (entry == null) {
                throw invalid("Choice probability must not be null");
            }
            Object value = choiceValue(entry.value());
            double p = probability(entry.probability());
            if (!expected.contains(value) || !seen.add(value)) {
                throw invalid("Unknown or duplicate choice probability");
            }
            sum += p;
            probabilities.add(new DecisionAnswer.ChoiceProbability(value, p));
        }
        validateSum(sum, expected.size());
        if (!seen.contains(selected)) {
            throw invalid("Selected choice must be one of the supplied options");
        }
        return new DecisionAnswer.Choice(selected, probability(answer.confidence()), probabilities);
    }

    private static DecisionAnswer.Score parseScore(
            DecisionQuestion.Score question, Decision.Answer.Score answer) {
        if (answer.probabilities().size() != question.levels().size()) {
            throw invalid("Probability distribution must contain every level");
        }
        var seen = new HashSet<Long>();
        List<DecisionAnswer.ScoreProbability> probabilities = new ArrayList<>();
        double sum = 0;
        double expectation = 0;
        for (var entry : answer.probabilities()) {
            if (entry == null) {
                throw invalid("Score probability must not be null");
            }
            long index = entry.value();
            String label = entry.label();
            if (index < 0
                    || index >= question.levels().size()
                    || !seen.add(index)
                    || !question.levels().get((int) index).label().equals(label)) {
                throw invalid("Unknown, duplicate or mismatched score level");
            }
            double p = probability(entry.probability());
            sum += p;
            expectation += index * p;
            probabilities.add(new DecisionAnswer.ScoreProbability((int) index, label, p));
        }
        validateSum(sum, question.levels().size());
        double score = answer.score();
        if (!Double.isFinite(score)
                || score < 0
                || score > question.levels().size() - 1
                || Math.abs(score - expectation) > TOLERANCE * question.levels().size()) {
            throw invalid("Score must match the probability-weighted level indices");
        }
        return new DecisionAnswer.Score(score, probability(answer.confidence()), probabilities);
    }

    private static void validateSum(double sum, int size) {
        if (Math.abs(sum - 1) > TOLERANCE * size) {
            throw invalid("Probabilities must sum to one");
        }
    }

    private static Object choiceValue(DecisionChoiceValue value) {
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBool()) {
            return value.asBool();
        }
        throw invalid("Choice value must be a string or boolean");
    }

    private static double probability(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw invalid("Probability or confidence outside [0, 1]");
        }
        return value;
    }

    private static <T> T optional(JsonField<T> field) {
        if (field.isMissing() || field.isNull()) {
            return null;
        }
        return field.asKnown().orElseThrow(() -> invalid("Invalid optional response field"));
    }

    private static Long count(JsonField<Long> field) {
        Long value = optional(field);
        if (value != null && value < 0) {
            throw invalid("Token usage must be a nonnegative integer");
        }
        return value;
    }

    private static OpenAIDecisionsException invalid(String message) {
        return new OpenAIDecisionsException(message, null, false);
    }

    public static final class Builder {
        private String apiKey = System.getenv("OPENAI_API_KEY");
        private String baseUrl = DEFAULT_BASE_URL;
        private String model = DEFAULT_MODEL;
        private Duration timeout = DEFAULT_TIMEOUT;
        private OpenAIClient client;

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

        /**
         * Borrows an SDK client instead of creating one. The supplied client controls credentials,
         * base URL and retry settings; this wrapper still applies its model and request timeout.
         */
        public Builder client(OpenAIClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public OpenAIDecisionsClient build() {
            return new OpenAIDecisionsClient(this);
        }
    }
}
