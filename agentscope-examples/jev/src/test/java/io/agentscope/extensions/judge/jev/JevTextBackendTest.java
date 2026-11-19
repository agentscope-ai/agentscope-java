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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JevTextBackendTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static String reply(String content) {
        try {
            return JSON.writeValueAsString(
                    Map.of(
                            "model",
                            "text-test",
                            "usage",
                            Map.of("prompt_tokens", 90, "completion_tokens", 15),
                            "choices",
                            List.of(
                                    Map.of(
                                            "finish_reason",
                                            "stop",
                                            "message",
                                            Map.of("content", content)))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void chatModelUsesSameTypedQuestionsAndReturnsUncalibratedDistributions() {
        var request =
                SystemOneRequest.builder()
                        .state(Map.of("evidence", "synthetic"))
                        .question("a", new NoulQuestion("true?", null))
                        .question(
                                "b", new ChoiceQuestion("which?", Map.of("yes", "yes", "no", "no")))
                        .question("c", new ScoreQuestion("how?", List.of("low", "high")))
                        .build();
        var backend =
                new JevTextBackend(
                        "qwen-test",
                        body -> {
                            assertTrue(body.contains("json_object"));
                            assertTrue(body.contains("synthetic"));
                            assertFalse(body.contains("Authorization"));
                            return Mono.just(
                                    reply(
                                            "{\"a\":0.9,\"b\":{\"yes\":0.9,\"no\":0.1},\"c\":{\"0\":0.25,\"1\":0.75}}"));
                        });
        var r = backend.apply(request).block();
        assertEquals("llm:text-test", r.model());
        assertEquals(90, r.usage().inputTokens());
        assertEquals(.75, ((ScoreAnswer) r.answers().get("c")).score());
        assertEquals(.8, ((ChoiceAnswer) r.answers().get("b")).confidence(), 1e-9);
    }

    @Test
    void malformedRepliesAreErrorsWithUsageNotInventedProbabilities() {
        for (String bad :
                List.of(
                        "{}",
                        "{\"completeness.q\":2}",
                        "{\"completeness.q\":{\"0\":0,\"1\":0,\"2\":0,\"3\":0}}",
                        "{\"completeness.q\":{\"0\":0,\"1\":0,\"2\":0,\"3\":1}} extra",
                        "{\"completeness.q\":{\"0\":0,\"1\":0,\"2\":0,\"3\":1,\"3\":0}}")) {
            var backend = new JevTextBackend("qwen-test", body -> Mono.just(reply(bad)));
            var evaluator =
                    new JevTraceEvaluator(
                            backend,
                            List.of(
                                    JevTraceMetrics.completeness(
                                            JevTraceEvaluationTest.THRESHOLDS)),
                            JevTraceEvaluator.Limits.defaults());
            var report = evaluator.evaluate(JevTraceEvaluationTest.sample("bad")).block();
            assertEquals("INVALID_RESPONSE", report.result("completeness").reason());
            assertEquals(15, report.usage().get(0).usage().outputTokens());
            assertFalse(report.passed());
        }
    }
}
