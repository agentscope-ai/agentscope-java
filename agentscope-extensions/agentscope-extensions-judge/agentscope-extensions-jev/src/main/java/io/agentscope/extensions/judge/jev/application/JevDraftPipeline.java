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

package io.agentscope.extensions.judge.jev.application;

import io.agentscope.extensions.judge.jev.JevJudge;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Buffers draft text before release; revision functions must never re-execute side-effectful tools. */
public final class JevDraftPipeline {
    public record Revision(String draft, JevJudge.Result review, int attempt) {}

    public record Outcome(
            String publishedText,
            String draftText,
            JevJudge.Result review,
            int revisions,
            String reason) {
        public Outcome(String publishedText, JevJudge.Result review, int revisions, String reason) {
            this(publishedText, publishedText, review, revisions, reason);
        }
    }

    private final JevJudge judge;
    private final int maxChars;
    private final int maxRevisions;
    private final Duration budget;

    public JevDraftPipeline(JevJudge judge, int maxChars, int maxRevisions, Duration budget) {
        this.judge = Objects.requireNonNull(judge);
        this.budget = Objects.requireNonNull(budget);
        if (maxChars < 1
                || maxRevisions < 0
                || maxRevisions > 10
                || budget.isZero()
                || budget.isNegative())
            throw new IllegalArgumentException("invalid pipeline limits");
        this.maxChars = maxChars;
        this.maxRevisions = maxRevisions;
    }

    public Mono<Outcome> run(
            Object request,
            Object evidence,
            JevJudge.Definition inputChecks,
            JevJudge.Definition outputChecks,
            Supplier<Flux<String>> generate,
            Function<Revision, Flux<String>> revise) {
        return Mono.defer(
                        () ->
                                judge.judge(request, inputChecks)
                                        .flatMap(
                                                input -> {
                                                    if (input.status() != JevJudge.Status.PASS)
                                                        return Mono.just(
                                                                new Outcome(
                                                                        null,
                                                                        input,
                                                                        0,
                                                                        "INPUT_NOT_ACCEPTED"));
                                                    return collect(generate)
                                                            .flatMap(
                                                                    text ->
                                                                            review(
                                                                                    request,
                                                                                    evidence,
                                                                                    outputChecks,
                                                                                    text,
                                                                                    revise,
                                                                                    0));
                                                }))
                .timeout(budget)
                .onErrorResume(
                        e ->
                                Mono.just(
                                        new Outcome(
                                                null,
                                                null,
                                                0,
                                                e instanceof java.util.concurrent.TimeoutException
                                                        ? "TIMEOUT"
                                                        : e instanceof DraftTooLarge
                                                                ? "DRAFT_TOO_LARGE"
                                                                : "PIPELINE_ERROR")));
    }

    private Mono<Outcome> review(
            Object request,
            Object evidence,
            JevJudge.Definition checks,
            String text,
            Function<Revision, Flux<String>> revise,
            int attempt) {
        return judge.judge(Map.of("request", request, "evidence", evidence, "draft", text), checks)
                .flatMap(
                        result -> {
                            if (result.status() == JevJudge.Status.PASS)
                                return Mono.just(new Outcome(text, result, attempt, "ACCEPTED"));
                            if (result.status() != JevJudge.Status.FAIL || attempt >= maxRevisions)
                                return Mono.just(
                                        new Outcome(
                                                null, text, result, attempt, "DRAFT_NOT_ACCEPTED"));
                            return collect(
                                            () ->
                                                    revise.apply(
                                                            new Revision(
                                                                    text, result, attempt + 1)))
                                    .flatMap(
                                            next ->
                                                    next.equals(text)
                                                            ? Mono.just(
                                                                    new Outcome(
                                                                            null,
                                                                            text,
                                                                            result,
                                                                            attempt + 1,
                                                                            "UNCHANGED_DRAFT"))
                                                            : review(
                                                                    request,
                                                                    evidence,
                                                                    checks,
                                                                    next,
                                                                    revise,
                                                                    attempt + 1));
                        });
    }

    private Mono<String> collect(Supplier<Flux<String>> generate) {
        return Mono.defer(
                () ->
                        Flux.defer(generate)
                                .reduce(
                                        new StringBuilder(),
                                        (buffer, chunk) -> {
                                            if (chunk.length() > maxChars - buffer.length())
                                                throw new DraftTooLarge();
                                            return buffer.append(chunk);
                                        })
                                .map(StringBuilder::toString));
    }

    private static final class DraftTooLarge extends RuntimeException {}
}
