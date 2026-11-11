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

package io.agentscope.examples.jev;

import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.application.JevBrowserPlanner;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import io.agentscope.extensions.judge.jev.application.JevContextPlanner;
import io.agentscope.extensions.judge.jev.application.JevCustomerSupport;
import io.agentscope.extensions.judge.jev.application.JevDraftPipeline;
import io.agentscope.extensions.judge.jev.application.JevMemoryGate;
import io.agentscope.extensions.judge.jev.application.JevRag;
import io.agentscope.extensions.judge.jev.application.JevSupervisor;
import io.agentscope.extensions.judge.jev.application.JevTeamPlanner;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline application examples. Fixtures demonstrate composition, not model accuracy. */
public final class JevApplicationExample {
    private JevApplicationExample() {}

    public static void main(String[] args) {
        var options =
                new JevExecution.Options(
                        JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "demo-v1", (c, r) -> {});
        var selector = new JevCandidateSelector(JevApplicationExample::fake, options, 0.2, 0.8);
        var judge = new JevJudge(JevApplicationExample::fake, Duration.ofSeconds(1));
        System.out.println("OFFLINE fixtures; proposals only, no actions or external calls");
        System.out.println(
                new JevCustomerSupport(selector, judge)
                        .review(null, "查订单，不能发货就退款", "已退款", "尚未退款")
                        .block());
        var rag = new JevRag(selector, judge);
        var retrieval =
                rag.retrieve(
                                null,
                                "退款说明",
                                List.of(
                                        new JevRag.Document("public", "退款须审核", "v1"),
                                        new JevRag.Document("private", "private", "v1")),
                                d -> d.id().equals("public"),
                                3)
                        .block();
        System.out.println(retrieval);
        System.out.println(rag.verify("退款说明", "退款须审核", retrieval.evidence()).block());
        var check =
                new JevJudge.Definition(
                        "demo-check",
                        List.of(
                                new JevJudge.Criterion(
                                        "ok",
                                        new NoulQuestion("Acceptable?", null),
                                        true,
                                        0.2,
                                        0.8)));
        System.out.println(
                new JevDraftPipeline(judge, 1000, 1, Duration.ofSeconds(3))
                        .run(
                                "request",
                                "evidence",
                                check,
                                check,
                                () -> Flux.just("Reviewed draft"),
                                r -> Flux.just("Revised draft"))
                        .block());
        System.out.println(
                new JevSupervisor(judge).assess("task", "worker evidence", false).block());
        var plan =
                new JevContextPlanner(selector)
                        .plan(
                                null,
                                "task",
                                List.of(
                                        new JevContextPlanner.Exchange(
                                                "pair", "call", "result", true)))
                        .block();
        System.out.println("context restore=" + plan.restore());
        System.out.println(
                new JevMemoryGate(judge)
                        .assess(
                                "user",
                                new JevMemoryGate.Fact(
                                        "user", "prefers concise replies", "explicit user request"),
                                List.of())
                        .block());
        System.out.println(
                new JevTeamPlanner(selector)
                        .recommend(
                                null,
                                "review",
                                "task",
                                List.of(new JevTeamPlanner.Member("reviewer", "review code")),
                                m -> true)
                        .block());
        System.out.println(
                new JevBrowserPlanner(selector)
                        .propose(
                                null,
                                "read page",
                                "page-v1",
                                List.of(
                                        new JevBrowserPlanner.Action(
                                                "read",
                                                JevBrowserPlanner.Operation.READ,
                                                "visible content")),
                                () -> "page-v1",
                                false)
                        .block());
    }

    private static Mono<SystemOneResult> fake(SystemOneRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .keySet()
                .forEach(
                        id ->
                                answers.put(
                                        id,
                                        new NoulAnswer(
                                                id.equals("off_track") || id.equals("conflict")
                                                        ? 0.01
                                                        : 0.95)));
        return Mono.just(new SystemOneResult("offline-fixture", answers, null));
    }
}
