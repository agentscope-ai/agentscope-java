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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevApplicationScenariosTest {
    @Test
    void classificationKeepsBothBusinessQueues() {
        var r = JevApplicationScenarios.run("classification");
        assertEquals(List.of("orders", "billing"), r.get("selected"));
        assertEquals(List.of("technical"), r.get("rejected"));
    }

    @Test
    void contextPreservesPinnedPairAndRestoresOrder() {
        var r = JevApplicationScenarios.run("context");
        assertEquals(List.of("current-test"), r.get("retained"));
        assertEquals(List.of("old-search"), r.get("archived"));
        assertEquals(List.of("old-search", "current-test"), r.get("restored"));
    }

    @Test
    void memoryConflictCannotBeOffsetByDurability() {
        var r = JevApplicationScenarios.run("memory");
        assertEquals("PASS", r.get("withoutConflict"));
        assertEquals("FAIL", r.get("withConflict"));
    }

    @Test
    void supportKeepsTriageSeparateFromDraftFailure() {
        var r = JevApplicationScenarios.run("support");
        assertEquals(List.of("orders", "billing"), r.get("queues"));
        assertEquals("FAIL", r.get("review"));
    }

    @Test
    void teamExcludesUnavailableMemberBeforeJudgment() {
        var r = JevApplicationScenarios.run("team");
        assertEquals(List.of("reviewer"), r.get("recommended"));
        assertEquals(List.of("reviewer", "writer"), r.get("considered"));
    }

    @Test
    void browserRequiresCurrentPageAndIndependentCompletion() {
        var r = JevApplicationScenarios.run("browser");
        assertEquals("policy", r.get("current"));
        assertEquals(false, r.get("validForNewPage"));
        assertEquals("STALE_PAGE", r.get("stale"));
        assertEquals("COMPLETION_NOT_VERIFIED", r.get("unverifiedDone"));
    }

    @Test
    void judgeKeepsInconclusiveSeparateFromFail() {
        assertEquals(
                Map.of("supported", "PASS", "unsupported", "FAIL", "uncertain", "INCONCLUSIVE"),
                JevApplicationScenarios.run("judge"));
    }

    @Test
    void evaluationScoresOppositeLabelsWithoutSendingGold() {
        var r = JevApplicationScenarios.run("evaluation");
        assertEquals(2, r.get("cases"));
        assertEquals(2, r.get("fixtureMatches"));
        assertEquals(List.of("PASS", "FAIL"), r.get("verdicts"));
    }

    @Test
    void unknownScenarioDoesNotSilentlyRunAnotherExample() {
        assertThrows(IllegalArgumentException.class, () -> JevApplicationScenarios.run("--live"));
    }

    @Test
    void taskReviewRequiresHostVerificationEvenWithHighCompletionScore() {
        assertEquals(
                Map.of(
                        "withoutVerification",
                        "REQUEST_VERIFICATION",
                        "withVerification",
                        "REVIEW_COMPLETION"),
                JevApplicationScenarios.run("task-review"));
    }

    @Test
    void ragFiltersPrivateAndIrrelevantDocumentsAndRejectsEmptyEvidence() {
        var r = JevApplicationScenarios.run("rag");
        assertEquals(List.of("policy"), r.get("evidence"));
        assertEquals("PASS", r.get("supported"));
        assertEquals("FAIL", r.get("withoutEvidence"));
    }

    @Test
    void draftPublishesOnlyAcceptedRevisionAndDoesNotGenerateForRejectedInput() {
        var r = JevApplicationScenarios.run("draft");
        assertEquals("未发货订单可申请退款，到账以实际处理结果为准。", r.get("published"));
        assertEquals(1, r.get("revisions"));
        assertEquals(1, r.get("generationCalls"));
        assertEquals(1, r.get("revisionCalls"));
        assertEquals("UNCHANGED_DRAFT", r.get("unchanged"));
        assertEquals(false, r.get("unchangedPublished"));
        assertEquals("INPUT_NOT_ACCEPTED", r.get("inputRejected"));
        assertEquals(false, r.get("inputRejectedPublished"));
    }
}
