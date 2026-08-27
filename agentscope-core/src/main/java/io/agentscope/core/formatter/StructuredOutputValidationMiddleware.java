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
package io.agentscope.core.formatter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Response-side validation and error-feedback retry for structured outputs.
 *
 * <p>This middleware activates whenever the model call carries a JSON-schema
 * response format ({@code GenerateOptions.responseFormat()} of type
 * json_schema) — i.e. the provider-native structured output path. It validates
 * the model's final text against the same schema, and when validation fails it
 * feeds the actionable errors back into the next generation attempt, following
 * the industry-standard remediation pattern also used by Instructor, Guardrails
 * re-ask and Spring AI's structured-output validation.
 *
 * <p>It is <b>opt-in</b>: the framework keeps trusting
 * {@code Model.supportsNativeStructuredOutput()} capability declarations, and
 * this middleware is the integration point for callers that additionally want
 * framework-side verification of what the provider actually returned.
 *
 * <p>Configuration follows the framework's options style:
 * <pre>{@code
 * agent.middleware(new StructuredOutputValidationMiddleware(
 *         StructuredOutputRetryPolicy.builder()
 *                 .maxAttempts(3)
 *                 .tokenBudget(50_000L)          // cumulative guard
 *                 .onFailedAttempt(a -> log.info("attempt {} failed: {}", a.attemptNumber(), a.kind()))
 *                 .build()));
 * }</pre>
 * Per-call overrides are supported via
 * {@code GenerateOptions.builder().structuredOutputPolicy(...)}.
 *
 * <p><b>Event-stream contract</b>: events of a <em>failed</em> attempt are not
 * forwarded downstream — they would surface non-conforming content the
 * middleware is about to correct. Instead, one {@link CustomEvent} named
 * {@value #FAILED_ATTEMPT_EVENT} is emitted per failed attempt (disable via
 * {@code emitAttemptEvents(false)}), and only the events of the final,
 * conforming attempt are released. When retries are exhausted the stream fails
 * with a {@link StructuredOutputValidationException} carrying every failed
 * attempt chronologically.
 *
 * <p><b>Streaming limitation</b>: like the reference implementations (Spring AI
 * structured-output validation, Instructor retry), correction retries require
 * the complete response — validation is only active for non-streaming calls
 * ({@code stream != true}). Streaming calls pass through untouched.
 */
public class StructuredOutputValidationMiddleware implements MiddlewareBase {

    /** Custom-event name emitted for every failed generation attempt. */
    public static final String FAILED_ATTEMPT_EVENT = "structured_output.failed_attempt";

    private static final Logger log =
            LoggerFactory.getLogger(StructuredOutputValidationMiddleware.class);

    private final StructuredOutputRetryPolicy policy;

    public StructuredOutputValidationMiddleware() {
        this(StructuredOutputRetryPolicy.defaults());
    }

    public StructuredOutputValidationMiddleware(StructuredOutputRetryPolicy policy) {
        this.policy = policy != null ? policy : StructuredOutputRetryPolicy.defaults();
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        ResponseFormat responseFormat =
                input.options() == null ? null : input.options().getResponseFormat();
        JsonSchema schema = responseFormat == null ? null : responseFormat.getJsonSchema();
        if (schema == null) {
            return next.apply(input); // not a structured-output call: pass through untouched
        }
        if (Boolean.TRUE.equals(input.options().getStream())) {
            // Streaming: correction retries need the complete response (see class javadoc)
            log.debug("Structured output validation skipped for streaming call");
            return next.apply(input);
        }
        StructuredOutputRetryPolicy effective = policy;
        if (input.options().structuredOutputPolicy() != null) {
            effective = input.options().structuredOutputPolicy();
        }
        return attempt(input, schema, effective, next, 1, 0L, List.of());
    }

    private Flux<AgentEvent> attempt(
            ModelCallInput input,
            JsonSchema schema,
            StructuredOutputRetryPolicy effective,
            Function<ModelCallInput, Flux<AgentEvent>> next,
            int attemptNumber,
            long accumulatedTokens,
            List<FailedAttempt> failedSoFar) {
        return next.apply(input)
                .collectList()
                .flatMapMany(
                        events -> {
                            String rawText = aggregateText(events);
                            ChatUsage usage = usageOf(events);
                            long attemptTokens =
                                    usage == null
                                            ? 0
                                            : usage.getInputTokens() + usage.getOutputTokens();
                            long runningTokens = accumulatedTokens + attemptTokens;
                            FailedAttempt.Kind kind;
                            List<StructuredOutputValidator.ValidationError> errors;
                            String parseError = null;
                            try {
                                JsonNode payload =
                                        StructuredOutputGenerator.extractJsonObject(rawText);
                                errors = StructuredOutputValidator.validate(payload, schema);
                                kind =
                                        errors.isEmpty()
                                                ? null
                                                : FailedAttempt.Kind.VALIDATION_ERROR;
                            } catch (StructuredOutputParseException parseException) {
                                errors = List.of();
                                parseError = parseException.getMessage();
                                kind = FailedAttempt.Kind.PARSE_ERROR;
                            }
                            if (kind == null) {
                                return Flux.fromIterable(
                                        events); // conforms: release original stream
                            }
                            FailedAttempt failed =
                                    new FailedAttempt(
                                            attemptNumber,
                                            kind,
                                            errors,
                                            parseError,
                                            rawText,
                                            usage == null ? null : (long) usage.getInputTokens(),
                                            usage == null ? null : (long) usage.getOutputTokens());
                            recordFailedAttempt(effective, failed);
                            List<FailedAttempt> allFailed = new ArrayList<>(failedSoFar);
                            allFailed.add(failed);
                            // Failed attempt: swallow its events (they would surface
                            // non-conforming content); emit one observable custom event
                            // per failed attempt when enabled.
                            Flux<AgentEvent> attemptEvent =
                                    effective.emitAttemptEvents()
                                            ? Flux.just(failedAttemptEvent(failed))
                                            : Flux.empty();
                            if (attemptNumber >= effective.maxAttempts()
                                    || (effective.tokenBudget() != null
                                            && runningTokens >= effective.tokenBudget())) {
                                log.debug(
                                        "Structured output rejected after {} attempts (tokens={})",
                                        attemptNumber,
                                        runningTokens);
                                return Flux.concat(
                                        attemptEvent,
                                        Flux.error(
                                                new StructuredOutputValidationException(
                                                        schema.getName(),
                                                        errors,
                                                        parseError,
                                                        allFailed)));
                            }
                            return Flux.concat(
                                    attemptEvent,
                                    attempt(
                                            correctedInput(input, failed),
                                            schema,
                                            effective,
                                            next,
                                            attemptNumber + 1,
                                            runningTokens,
                                            allFailed));
                        });
    }

    private ModelCallInput correctedInput(ModelCallInput input, FailedAttempt failed) {
        String feedback =
                StructuredOutputGenerator.retryPrompt(
                        failed.kind() == FailedAttempt.Kind.VALIDATION_ERROR
                                ? failed.validationErrors()
                                : List.of(
                                        StructuredOutputGenerator.parseErrorAsValidationError(
                                                failed.parseErrorMessage())));
        Msg correction =
                Msg.builderForRole(MsgRole.USER)
                        .name("structured_output_correction")
                        .content(TextBlock.builder().text(feedback).build())
                        .build();
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(correction);
        return new ModelCallInput(messages, input.tools(), input.options(), input.model());
    }

    private void recordFailedAttempt(StructuredOutputRetryPolicy effective, FailedAttempt failed) {
        if (effective.onFailedAttempt() != null) {
            try {
                effective.onFailedAttempt().accept(failed);
            } catch (RuntimeException listenerFailure) {
                log.warn("onFailedAttempt listener failed: {}", listenerFailure.getMessage());
            }
        }
        log.info(
                "Structured output attempt {} failed ({}) — feeding errors back",
                failed.attemptNumber(),
                failed.kind());
    }

    private static String aggregateText(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof TextBlockDeltaEvent delta) {
                sb.append(delta.getDelta());
            }
        }
        return sb.toString();
    }

    private static ChatUsage usageOf(List<AgentEvent> events) {
        ChatUsage usage = null;
        for (AgentEvent event : events) {
            if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
                usage = end.getUsage();
            }
        }
        return usage;
    }

    /** Builds the failed-attempt event for stream visibility. */
    public static AgentEvent failedAttemptEvent(FailedAttempt failed) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("attemptNumber", failed.attemptNumber());
        value.put("kind", failed.kind().name());
        if (failed.rawOutput() != null) {
            value.put(
                    "rawOutput",
                    failed.rawOutput().length() > 500
                            ? failed.rawOutput().substring(0, 500)
                            : failed.rawOutput());
        }
        if (!failed.validationErrors().isEmpty()) {
            value.put(
                    "errors",
                    failed.validationErrors().stream()
                            .map(e -> e.instanceLocation() + ": " + e.message())
                            .limit(5)
                            .toList());
        }
        if (failed.parseErrorMessage() != null) {
            value.put("parseError", failed.parseErrorMessage());
        }
        return new CustomEvent(FAILED_ATTEMPT_EVENT, value);
    }
}
