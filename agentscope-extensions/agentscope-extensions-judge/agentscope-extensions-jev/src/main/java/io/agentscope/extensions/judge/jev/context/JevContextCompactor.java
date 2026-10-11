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

package io.agentscope.extensions.judge.jev.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactionStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Paired call/result pruning adapted from fast-jev-compaction, with scoped durable recovery. */
public final class JevContextCompactor implements ConversationCompactionStrategy {
    public enum Action {
        KEEP,
        TRUNCATE_RESULT,
        DROP_PAIR
    }

    public record Config(
            double discardThreshold,
            double keepThreshold,
            int preserveRecentMessages,
            int maxStateTokens,
            int maxRequestTokens,
            int maxQuestions,
            int truncateHeadChars,
            double minimumReduction,
            int maxTranscriptChars,
            Set<String> eligibleTools,
            Set<String> pinnedMessageIds) {
        public Config {
            if (!Double.isFinite(discardThreshold)
                    || !Double.isFinite(keepThreshold)
                    || discardThreshold < 0
                    || keepThreshold > 1
                    || discardThreshold >= keepThreshold
                    || preserveRecentMessages < 0
                    || maxStateTokens < 1
                    || maxRequestTokens <= maxStateTokens
                    || maxQuestions < 2
                    || maxQuestions > 512
                    || truncateHeadChars < 0
                    || !Double.isFinite(minimumReduction)
                    || minimumReduction < 0
                    || minimumReduction >= 1
                    || maxTranscriptChars < 1)
                throw new IllegalArgumentException("invalid compaction config");
            eligibleTools = Set.copyOf(eligibleTools);
            pinnedMessageIds = Set.copyOf(pinnedMessageIds);
        }

        /** Demonstration thresholds only: calibrate before enforcing. Empty tools means no candidates. */
        public static Config defaults() {
            return new Config(.2, .8, 6, 25000, 30000, 128, 300, .1, 5_000_000, Set.of(), Set.of());
        }
    }

    public record Scores(double keepCall, double keepResult, String reason) {}

    public record Plan(
            List<Msg> messages,
            Map<String, Action> decisions,
            Map<String, Scores> scores,
            int requests,
            long inputTokens,
            long outputTokens,
            String stateStage,
            String archiveReference) {
        public Plan {
            messages = List.copyOf(messages);
            decisions = Map.copyOf(decisions);
            scores = Map.copyOf(scores);
        }
    }

    private final Function<SystemOneRequest, Mono<SystemOneResult>> caller;
    private final Config config;
    private final JevExecution execution;
    private final JevContextArchive archive;

    public JevContextCompactor(JevClient client) {
        this(client::systemOne, Config.defaults(), JevExecution.Options.disabled(), null);
    }

    public JevContextCompactor(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            Config config,
            JevExecution.Options options,
            JevContextArchive archive) {
        this.caller = Objects.requireNonNull(caller);
        this.config = Objects.requireNonNull(config);
        this.execution = new JevExecution("context_compaction", options);
        this.archive = archive;
        if (options.mode() == JevExecution.Mode.ENFORCE && archive == null)
            throw new IllegalArgumentException("ENFORCE requires durable archive");
    }

    @Override
    public Mono<Optional<List<Msg>>> compact(Request request) {
        return plan(request)
                .map(
                        decision -> {
                            // OFF and SHADOW preserve the existing compaction flow exactly.
                            if (execution.mode() != JevExecution.Mode.ENFORCE)
                                return Optional.empty();
                            // ENFORCE is conservative: failure does not invoke a second, lossy
                            // summary policy.
                            return Optional.of(
                                    decision.status() == JevExecution.Status.DECIDED
                                            ? decision.value().messages()
                                            : request.messages());
                        });
    }

    public Mono<JevExecution.Decision<Plan>> plan(Request request) {
        return execution.execute(request.context(), () -> evaluate(request));
    }

    private Mono<JevExecution.Decision<Plan>> evaluate(Request request) {
        JevContextArchive.Scope scope =
                execution.mode() == JevExecution.Mode.ENFORCE
                        ? new JevContextArchive.Scope(
                                request.context().getUserId(),
                                request.agentId(),
                                request.sessionId())
                        : null;
        CompactionTranscript transcript = new CompactionTranscript(request.messages(), config);
        var candidates = transcript.pairs.stream().filter(p -> !p.pinned()).toList();
        if (candidates.isEmpty())
            return Mono.just(
                    new JevExecution.Decision<>(
                            JevExecution.Status.SKIPPED, null, "NO_CANDIDATES"));
        var fitted = transcript.fit(config);
        List<SystemOneRequest> batches = batches(candidates, fitted);
        Map<String, Action> actions = new LinkedHashMap<>();
        Map<String, Scores> scores = new LinkedHashMap<>();
        transcript.pairs.forEach(p -> actions.put(p.key(), Action.KEEP));
        transcript.pairs.stream()
                .filter(CompactionTranscript.Pair::pinned)
                .forEach(p -> scores.put(p.key(), new Scores(1, 1, "PINNED")));
        return Flux.fromIterable(batches)
                .concatMap(
                        batch ->
                                Mono.defer(() -> caller.apply(batch))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new IllegalArgumentException(
                                                                "empty compaction reply")))
                                        .map(
                                                result -> {
                                                    JevClient.validateResponse(batch, result);
                                                    return result;
                                                }),
                        1)
                .collectList()
                .flatMap(
                        results -> {
                            if (!transcript.encoded.equals(
                                    JsonUtils.getJsonCodec().toJson(request.messages())))
                                return Mono.just(
                                        JevExecution.Decision.<Plan>uncertain(
                                                "TRANSCRIPT_CHANGED"));
                            Map<String, Answer> answers = new HashMap<>();
                            long input = 0, output = 0;
                            for (var result : results) {
                                answers.putAll(result.answers());
                                input += result.usage().inputTokens();
                                output += result.usage().outputTokens();
                            }
                            for (var pair : candidates) {
                                double call =
                                        ((NoulAnswer) answers.get("call_" + pair.key())).noul();
                                double result =
                                        ((NoulAnswer) answers.get("result_" + pair.key())).noul();
                                Action action =
                                        result <= config.discardThreshold()
                                                        && call <= config.discardThreshold()
                                                ? Action.DROP_PAIR
                                                : result <= config.discardThreshold()
                                                                && call >= config.keepThreshold()
                                                        ? Action.TRUNCATE_RESULT
                                                        : Action.KEEP;
                                actions.put(pair.key(), action);
                                scores.put(
                                        pair.key(),
                                        new Scores(
                                                call,
                                                result,
                                                action != Action.KEEP
                                                        ? action.name()
                                                        : result >= config.keepThreshold()
                                                                ? "NEEDED_RESULT"
                                                                : "UNCERTAIN"));
                            }
                            List<Msg> retained =
                                    transcript.apply(actions, config.truncateHeadChars());
                            int after = JsonUtils.getJsonCodec().toJson(retained).length();
                            double reduction = 1 - (double) after / transcript.encoded.length();
                            if (after >= transcript.encoded.length()
                                    || reduction < config.minimumReduction())
                                return Mono.just(
                                        new JevExecution.Decision<Plan>(
                                                JevExecution.Status.INCONCLUSIVE,
                                                new Plan(
                                                        request.messages(),
                                                        actions,
                                                        scores,
                                                        batches.size(),
                                                        input,
                                                        output,
                                                        fitted.stage(),
                                                        ""),
                                                "INSUFFICIENT_REDUCTION",
                                                Map.of(
                                                        "requests",
                                                        Integer.toString(batches.size()),
                                                        "state_stage",
                                                        fitted.stage(),
                                                        "input_tokens",
                                                        Long.toString(input),
                                                        "output_tokens",
                                                        Long.toString(output))));
                            long knownInput = input, knownOutput = output;
                            Mono<String> saved =
                                    execution.mode() == JevExecution.Mode.ENFORCE
                                            ? archive.save(scope, transcript.messages)
                                                    .filter(ref -> !ref.isBlank())
                                                    .switchIfEmpty(
                                                            Mono.error(
                                                                    new IllegalStateException(
                                                                            "archive did not"
                                                                                    + " acknowledge"
                                                                                    + " snapshot")))
                                            : Mono.just("");
                            return saved.map(
                                    ref ->
                                            new JevExecution.Decision<>(
                                                    JevExecution.Status.DECIDED,
                                                    new Plan(
                                                            retained,
                                                            actions,
                                                            scores,
                                                            batches.size(),
                                                            knownInput,
                                                            knownOutput,
                                                            fitted.stage(),
                                                            ref),
                                                    execution.mode() == JevExecution.Mode.ENFORCE
                                                            ? "ARCHIVED_PROPOSAL"
                                                            : "SHADOW_PROPOSAL",
                                                    Map.of(
                                                            "requests",
                                                            Integer.toString(batches.size()),
                                                            "state_stage",
                                                            fitted.stage(),
                                                            "pairs_removed",
                                                            Long.toString(
                                                                    actions.values().stream()
                                                                            .filter(
                                                                                    a ->
                                                                                            a
                                                                                                    == Action
                                                                                                            .DROP_PAIR)
                                                                            .count()),
                                                            "results_truncated",
                                                            Long.toString(
                                                                    actions.values().stream()
                                                                            .filter(
                                                                                    a ->
                                                                                            a
                                                                                                    == Action
                                                                                                            .TRUNCATE_RESULT)
                                                                            .count()),
                                                            "reduction",
                                                            Double.toString(reduction),
                                                            "archive_reference",
                                                            ref,
                                                            "input_tokens",
                                                            Long.toString(knownInput),
                                                            "output_tokens",
                                                            Long.toString(knownOutput))));
                        });
    }

    private List<SystemOneRequest> batches(
            List<CompactionTranscript.Pair> calls, CompactionTranscript.Fitted state) {
        List<SystemOneRequest> result = new ArrayList<>();
        Map<String, Question> current = new LinkedHashMap<>();
        for (var call : calls) {
            Map<String, Question> own =
                    Map.of(
                            "call_" + call.key(),
                                    new NoulQuestion(
                                            "Knowing that tool call "
                                                    + call.key()
                                                    + " occurred, with its input, is still needed"
                                                    + " to continue this task correctly.",
                                            null),
                            "result_" + call.key(),
                                    new NoulQuestion(
                                            "The full result of tool call "
                                                    + call.key()
                                                    + " is still needed verbatim for the next work;"
                                                    + " removing it from active context risks"
                                                    + " losing necessary evidence.",
                                            null));
            Map<String, Question> next = new LinkedHashMap<>(current);
            next.putAll(own);
            if (!current.isEmpty() && !fits(state, next)) {
                result.add(new SystemOneRequest(state.state(), null, Map.copyOf(current)));
                current.clear();
                next = new LinkedHashMap<>(own);
            }
            if (!fits(state, next))
                throw new IllegalArgumentException("no room for compaction questions");
            current.putAll(own);
        }
        if (!current.isEmpty())
            result.add(new SystemOneRequest(state.state(), null, Map.copyOf(current)));
        return result;
    }

    private boolean fits(CompactionTranscript.Fitted state, Map<String, Question> questions) {
        return questions.size() <= config.maxQuestions()
                && state.tokens()
                                + CompactionTranscript.estimate(
                                        JsonUtils.getJsonCodec().toJson(questions))
                                + 20
                        <= config.maxRequestTokens();
    }
}
