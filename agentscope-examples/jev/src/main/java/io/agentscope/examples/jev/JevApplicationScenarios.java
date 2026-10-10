/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.jev;

import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.extensions.judge.jev.evaluation.Evaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluationRunner;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Focused, offline user-guide scenarios. Scores are prescribed fixtures, never model measurements. */
public final class JevApplicationScenarios {
    public static final List<String> SCENARIOS =
            List.of(
                    "classification",
                    "context",
                    "memory",
                    "support",
                    "team",
                    "browser",
                    "judge",
                    "evaluation",
                    "task-review",
                    "rag",
                    "draft");

    private JevApplicationScenarios() {}

    public static void main(String[] args) {
        if (args.length > 1)
            throw new IllegalArgumentException("one scenario name or no arguments");
        System.out.println("OFFLINE: prescribed scores; no API, persistence or business action");
        for (String name : args.length == 0 ? SCENARIOS : List.of(args[0]))
            System.out.println(name + " " + run(name));
    }

    public static Map<String, Object> run(String name) {
        return switch (name) {
            case "classification" -> classification();
            case "context" -> context();
            case "memory" -> memory();
            case "support" -> support();
            case "team" -> team();
            case "browser" -> browser();
            case "judge" -> judge();
            case "evaluation" -> evaluation();
            case "task-review" -> taskReview();
            case "rag" -> rag();
            case "draft" -> draft();
            default -> throw new IllegalArgumentException("unknown scenario: " + name);
        };
    }

    private static RuntimeContext contextIdentity() {
        return RuntimeContext.builder().userId("demo-user").sessionId("demo-session").build();
    }

    private static JevExecution.Options options() {
        return new JevExecution.Options(
                JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "guide-v1", (ctx, record) -> {});
    }

    /** Match explicit candidate identities to prescribed probabilities, not inferred semantic labels. */
    private static JevCandidateSelector selector(Map<String, Double> prescribed) {
        return new JevCandidateSelector(
                request -> {
                    var answers = new LinkedHashMap<String, Answer>();
                    request.questions()
                            .forEach(
                                    (id, question) -> {
                                        Object candidate =
                                                ((Map<?, ?>)
                                                                ((NoulQuestion) question)
                                                                        .instructions())
                                                        .get("candidate");
                                        String identity;
                                        if (candidate instanceof JevContextPlanner.Exchange e)
                                            identity = e.id();
                                        else if (candidate
                                                instanceof JevBrowserPlanner.Action action)
                                            identity = action.id();
                                        else if (candidate instanceof JevRag.Document document)
                                            identity = document.id();
                                        else identity = String.valueOf(candidate);
                                        answers.put(
                                                id,
                                                new NoulAnswer(
                                                        prescribed.getOrDefault(identity, .5)));
                                    });
                    return Mono.just(
                            new SystemOneResult("prescribed-guide-fixture", answers, null));
                },
                options(),
                .2,
                .8);
    }

    private static Function<SystemOneRequest, Mono<SystemOneResult>> scores(
            Map<String, Double> prescribed) {
        return request -> {
            var answers = new LinkedHashMap<String, Answer>();
            request.questions()
                    .keySet()
                    .forEach(
                            id -> answers.put(id, new NoulAnswer(prescribed.getOrDefault(id, .5))));
            return Mono.just(new SystemOneResult("prescribed-guide-fixture", answers, null));
        };
    }

    private static Map<String, Object> classification() {
        var selector = selector(Map.of("订单查询", .95, "退款和账单", .90, "技术故障", .05));
        var decision =
                selector.select(
                                contextIdentity(),
                                Map.of("request", "订单没发货，如果今天还发不了请退款"),
                                "Does this request need the candidate support queue?",
                                Map.of("orders", "订单查询", "billing", "退款和账单", "technical", "技术故障"))
                        .block();
        return Map.of(
                "status",
                decision.status().name(),
                "selected",
                decision.value().selected(),
                "rejected",
                decision.value().rejected(),
                "actionsExecuted",
                0);
    }

    private static Map<String, Object> context() {
        var exchanges =
                List.of(
                        new JevContextPlanner.Exchange(
                                "old-search", "search(old issue)", "旧版本资料", false),
                        new JevContextPlanner.Exchange(
                                "current-test", "test(current change)", "当前测试证据", true));
        var plan =
                new JevContextPlanner(selector(Map.of("old-search", .05)))
                        .plan(contextIdentity(), "根据当前测试证据修复问题", exchanges)
                        .block();
        return Map.of(
                "retained",
                plan.retained().stream().map(JevContextPlanner.Exchange::id).toList(),
                "archived",
                plan.archive().keySet().stream().sorted().toList(),
                "restored",
                plan.restore().stream().map(JevContextPlanner.Exchange::id).toList(),
                "toolsReplayed",
                0);
    }

    private static Map<String, Object> memory() {
        var candidate = new JevMemoryGate.Fact("user-42", "偏好简洁的回答", "本轮用户明确要求");
        var existing = List.of(new JevMemoryGate.Fact("user-42", "偏好详尽的回答", "此前用户明确要求"));
        var conflict =
                new JevMemoryGate(
                                new JevJudge(
                                        scores(Map.of("durable", .95, "conflict", .95)),
                                        Duration.ofSeconds(2)))
                        .assess(
                                "user-42",
                                new JevMemoryGate.Fact("user-42", "偏好简洁的回答", "导入笔记中的偏好摘要，未记录生效时间"),
                                existing)
                        .block();
        var accepted =
                new JevMemoryGate(
                                new JevJudge(
                                        scores(Map.of("durable", .95, "conflict", .01)),
                                        Duration.ofSeconds(2)))
                        .assess("user-42", candidate, List.of())
                        .block();
        return Map.of(
                "withoutConflict",
                accepted.status().name(),
                "withConflict",
                conflict.status().name(),
                "writes",
                0);
    }

    private static Map<String, Object> support() {
        var selector =
                selector(
                        Map.of(
                                "Order status and shipping",
                                .95,
                                "Payments and conditional refund requests",
                                .9,
                                "Technical failures",
                                .05));
        var judge =
                new JevJudge(
                        scores(Map.of("covers_request", .95, "unsupported_commitment", .95)),
                        Duration.ofSeconds(2));
        var report =
                new JevCustomerSupport(selector, judge)
                        .review(
                                contextIdentity(),
                                "若今天无法发货，请申请退款",
                                "退款已经完成",
                                Map.of("refund", "尚未执行"))
                        .block();
        return Map.of(
                "queues",
                report.triage().value().selected(),
                "review",
                report.review().status().name(),
                "refunds",
                0,
                "messagesSent",
                0);
    }

    private static Map<String, Object> team() {
        var members =
                List.of(
                        new JevTeamPlanner.Member("reviewer", "代码评审与测试证据核查"),
                        new JevTeamPlanner.Member("writer", "撰写用户文档"),
                        new JevTeamPlanner.Member("offline-reviewer", "代码评审与测试证据核查"));
        var allowed = Set.of("reviewer", "writer");
        var report =
                new JevTeamPlanner(selector(Map.of("代码评审与测试证据核查", .95, "撰写用户文档", .05)))
                        .recommend(
                                contextIdentity(),
                                "review",
                                "检查退款补丁是否满足验收条件",
                                members,
                                member -> allowed.contains(member.id()))
                        .block();
        return Map.of(
                "recommended",
                report.value().selected(),
                "considered",
                report.value().scores().keySet().stream().sorted().toList(),
                "tasksStarted",
                0);
    }

    private static Map<String, Object> browser() {
        var planner = new JevBrowserPlanner(selector(Map.of("policy", .95, "done", .95)));
        var read =
                List.of(
                        new JevBrowserPlanner.Action(
                                "policy", JevBrowserPlanner.Operation.READ, "当前可见的退款条款"));
        var current =
                planner.propose(
                                contextIdentity(),
                                "读取退款期限",
                                "page-v1",
                                read,
                                () -> "page-v1",
                                false)
                        .block();
        var stale =
                planner.propose(
                                contextIdentity(),
                                "读取退款期限",
                                "page-v1",
                                read,
                                () -> "page-v2",
                                false)
                        .block();
        var done =
                planner.propose(
                                contextIdentity(),
                                "读取退款期限",
                                "page-v1",
                                List.of(
                                        new JevBrowserPlanner.Action(
                                                "done", JevBrowserPlanner.Operation.DONE, "完成")),
                                () -> "page-v1",
                                false)
                        .block();
        return Map.of(
                "current",
                current.action().id(),
                "validForNewPage",
                current.validFor("page-v2"),
                "stale",
                stale.reason(),
                "unverifiedDone",
                done.reason(),
                "actionsExecuted",
                0);
    }

    public static JevJudge.Definition answerDefinition() {
        return new JevJudge.Definition(
                "refund-answer-v1",
                List.of(
                        new JevJudge.Criterion(
                                "supported",
                                new NoulQuestion(
                                        "Is assistant_answer supported by supporting_context"
                                                + " without inventing completed refunds?",
                                        null),
                                true,
                                .2,
                                .8)));
    }

    private static Map<String, Object> judge() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : Map.of("supported", .95, "unsupported", .05, "uncertain", .5).entrySet()) {
            var judge =
                    new JevJudge(
                            scores(Map.of("supported", entry.getValue())), Duration.ofSeconds(2));
            var verdict =
                    judge.judge(
                                    Map.of(
                                            "user_question",
                                            "退款完成了吗？",
                                            "supporting_context",
                                            List.of("尚未执行退款"),
                                            "assistant_answer",
                                            "将根据查询结果办理"),
                                    answerDefinition())
                            .block();
            result.put(entry.getKey(), verdict.status().name());
        }
        return result;
    }

    private static Map<String, Object> taskReview() {
        var supervisor =
                new JevSupervisor(
                        new JevJudge(
                                scores(Map.of("complete", .95, "off_track", .01)),
                                Duration.ofSeconds(2)));
        var evidence = Map.of("changes", "退款前已添加订单状态检查", "testPlan", "覆盖已发货和未发货两条分支");
        var missing = supervisor.assess("修复退款条件检查", evidence, false).block();
        var verified = supervisor.assess("修复退款条件检查", evidence, true).block();
        return Map.of(
                "withoutVerification",
                missing.advice().name(),
                "withVerification",
                verified.advice().name());
    }

    private static Map<String, Object> rag() {
        var rag =
                new JevRag(
                        selector(Map.of("policy", .95, "delivery", .05, "private", .99)),
                        new JevJudge(scores(Map.of("grounded", .95)), Duration.ofSeconds(2)));
        var documents =
                List.of(
                        new JevRag.Document("policy", "未发货订单可以申请退款，需审核", "v1"),
                        new JevRag.Document("delivery", "物流轨迹查询方法", "v1"),
                        new JevRag.Document("private", "内部补偿政策", "v1"));
        var retrieval =
                rag.retrieve(
                                contextIdentity(),
                                "没发货可以退款吗？",
                                documents,
                                document -> !document.id().equals("private"),
                                2)
                        .block();
        var supported = rag.verify("没发货可以退款吗？", "可以申请退款，需审核", retrieval.evidence()).block();
        var empty = rag.verify("没发货可以退款吗？", "可以直接退款", List.of()).block();
        return Map.of(
                "evidence",
                retrieval.evidence().stream().map(JevRag.Document::id).toList(),
                "supported",
                supported.status().name(),
                "withoutEvidence",
                empty.status().name());
    }

    private static Map<String, Object> draft() {
        String good = "未发货订单可申请退款，到账以实际处理结果为准。";
        var input =
                new JevJudge.Definition(
                        "refund-input-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "in_scope",
                                        new NoulQuestion(
                                                "Is this a customer request about orders or"
                                                        + " refunds?",
                                                null),
                                        true,
                                        .2,
                                        .8)));
        var output =
                new JevJudge.Definition(
                        "refund-draft-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "supported",
                                        new NoulQuestion(
                                                "Is draft supported by evidence without inventing"
                                                        + " completed refunds or arrival of funds?",
                                                null),
                                        true,
                                        .2,
                                        .8)));
        var judge =
                new JevJudge(
                        request -> {
                            // Fixed fixture outcomes for these exact texts, not a semantic
                            // classifier.
                            boolean allowed =
                                    request.state() instanceof Map<?, ?> state
                                            ? good.equals(state.get("draft"))
                                            : "订单未发货时可以退款吗？".equals(request.state());
                            var answers = new LinkedHashMap<String, Answer>();
                            request.questions()
                                    .keySet()
                                    .forEach(
                                            id ->
                                                    answers.put(
                                                            id,
                                                            new NoulAnswer(allowed ? .95 : .05)));
                            return Mono.just(
                                    new SystemOneResult("prescribed-guide-fixture", answers, null));
                        },
                        Duration.ofSeconds(2));
        var pipeline = new JevDraftPipeline(judge, 16000, 1, Duration.ofSeconds(15));
        var generated = new AtomicInteger();
        var revised = new AtomicInteger();
        var accepted =
                pipeline.run(
                                "订单未发货时可以退款吗？",
                                good,
                                input,
                                output,
                                () -> {
                                    generated.incrementAndGet();
                                    return Flux.just("退款已经到账。");
                                },
                                revision -> {
                                    revised.incrementAndGet();
                                    return Flux.just(good);
                                })
                        .block();
        var unchanged =
                pipeline.run(
                                "订单未发货时可以退款吗？",
                                good,
                                input,
                                output,
                                () -> Flux.just("退款已经到账。"),
                                revision -> Flux.just(revision.draft()))
                        .block();
        var rejected =
                pipeline.run(
                                "无关请求",
                                good,
                                input,
                                output,
                                () -> {
                                    generated.incrementAndGet();
                                    return Flux.just(good);
                                },
                                revision -> Flux.just(good))
                        .block();
        return Map.of(
                "published",
                accepted.publishedText(),
                "revisions",
                accepted.revisions(),
                "generationCalls",
                generated.get(),
                "revisionCalls",
                revised.get(),
                "unchanged",
                unchanged.reason(),
                "unchangedPublished",
                unchanged.publishedText() != null,
                "inputRejected",
                rejected.reason(),
                "inputRejectedPublished",
                rejected.publishedText() != null);
    }

    private static Map<String, Object> evaluation() {
        var judge =
                new JevJudge(
                        request -> {
                            String answer =
                                    String.valueOf(
                                            ((Map<?, ?>) request.state()).get("assistant_answer"));
                            // Two explicitly scripted fixture answers; this is not a semantic
                            // classifier.
                            return scores(
                                            Map.of(
                                                    "supported",
                                                    answer.equals("尚未退款，需先核查状态") ? .95 : .05))
                                    .apply(request);
                        },
                        Duration.ofSeconds(2));
        Evaluator evaluator = new JevEvaluator(judge, answerDefinition());
        var evidence = List.of("业务记录：尚未执行退款");
        var cases =
                List.of(
                        new JevEvaluationRunner.Case(
                                "退款承诺",
                                new Evaluator.Request("honest", "退款了吗？", evidence, "尚未退款，需先核查状态"),
                                true),
                        new JevEvaluationRunner.Case(
                                "退款承诺",
                                new Evaluator.Request("invented", "退款了吗？", evidence, "退款已经完成"),
                                false));
        var report = new JevEvaluationRunner().run(evaluator, cases, 2).block();
        return Map.of(
                "cases",
                report.overall().total(),
                "fixtureMatches",
                report.overall().correct(),
                "verdicts",
                report.rows().stream()
                        .map(row -> row.response().verdict().status().name())
                        .toList());
    }
}
