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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * E2E benchmark tests for Skill recall and code execution recall.
 *
 * <p>Uses purpose-built benchmark skills under {@code src/test/resources/e2e-skills/} that are
 * designed to stress-test four dimensions:
 *
 * <ul>
 *   <li><b>Semantic discrimination</b>: {@code data-transform} vs {@code data-report} have
 *       intentionally similar domains — the LLM must distinguish format-conversion from
 *       statistical analysis.</li>
 *   <li><b>Distractor resistance</b>: {@code image-resize} is a decoy for non-image tasks; it
 *       must not be selected when the prompt has nothing to do with images.</li>
 *   <li><b>Fuzzy trigger</b>: {@code log-parser} should be triggered by indirect problem
 *       descriptions ("my app is crashing") without the user explicitly mentioning logs.</li>
 *   <li><b>Code execution recall</b>: {@code git-changelog} and others have pre-deployed scripts;
 *       the LLM must reference the script's absolute path rather than writing equivalent code
 *       inline.</li>
 * </ul>
 *
 * <p><b>Pass criterion:</b> overall recall rate &ge; 75% across all providers and scenarios.
 * Individual test methods never fail; {@link #assertRecallRates()} {@code @AfterAll} does.
 *
 * <p><b>Requirements:</b> {@code ENABLE_E2E_TESTS=true} + at least one API key env var.
 */
@Tag("e2e")
@Tag("skill")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Skill E2E Benchmark Tests")
class SkillE2ETest {

    private static final String SKILLS_CLASSPATH = "e2e-skills";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final double RECALL_THRESHOLD = 0.75;

    /** All benchmark skills registered simultaneously — the LLM must choose the right one. */
    private static final List<String> ALL_SKILL_NAMES =
            List.of("data-transform", "data-report", "image-resize", "log-parser", "git-changelog");

    // key = "SKILL_RECALL/<skillName>/<provider>"
    private static final Map<String, Boolean> RESULTS = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Skill recall benchmark scenarios
    //
    // Dimension 1 — Semantic discrimination (data-transform vs data-report)
    //   Both involve "data" but one is format conversion, the other is analysis.
    // Dimension 2 — Distractor resistance (image-resize must NOT fire for non-image tasks)
    // Dimension 3 — Fuzzy trigger (log-parser via indirect problem description)
    // -------------------------------------------------------------------------

    /** Semantic discrimination: must pick data-transform, not data-report. */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[semantic] data-transform: convert CSV to JSON")
    void testSkillRecall_dataTransform(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "data-transform",
                "I have a CSV file exported from Excel and I need to convert it to JSON"
                        + " so my frontend application can consume it.");
    }

    /** Semantic discrimination: must pick data-report, not data-transform. */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[semantic] data-report: summarize dataset statistics")
    void testSkillRecall_dataReport(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "data-report",
                "I have a CSV file with sales numbers across multiple regions and I need"
                        + " a summary showing averages, totals, and standard deviations.");
    }

    /** Fuzzy trigger: indirect description should still trigger log-parser. */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[fuzzy] log-parser: implicit debugging prompt")
    void testSkillRecall_logParser_fuzzy(ModelProvider provider) throws IOException {
        runSkillRecallTest(
                provider,
                "log-parser",
                "My app keeps crashing in production and I have no idea why."
                        + " Can you help me figure out what's going wrong?");
    }

    // -------------------------------------------------------------------------
    // Aggregate recall-rate assertion (runs once after all tests complete)
    // -------------------------------------------------------------------------

    @AfterAll
    static void assertRecallRates() {
        if (RESULTS.isEmpty()) {
            return;
        }

        long total = RESULTS.size();
        long hits = RESULTS.values().stream().filter(Boolean::booleanValue).count();
        double rate = total > 0 ? (double) hits / total : 1.0;

        String sep = "=".repeat(62);
        System.out.println("\n" + sep);
        System.out.println("  SKILL BENCHMARK RECALL SUMMARY");
        System.out.println(sep);

        System.out.println("\n  Skill recall:");
        RESULTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            String label = e.getKey().substring("SKILL_RECALL/".length());
                            System.out.printf(
                                    "    %s  %s%n", e.getValue() ? "[PASS]" : "[FAIL]", label);
                        });

        System.out.println();
        System.out.printf(
                "  Overall: %d / %d  (%.0f%%)   threshold >= %.0f%%%n",
                hits, total, rate * 100, RECALL_THRESHOLD * 100);
        System.out.println(sep + "\n");

        assertTrue(
                rate >= RECALL_THRESHOLD,
                String.format(
                        "Recall rate %.0f%% (%d/%d) is below the %.0f%% threshold.",
                        rate * 100, hits, total, RECALL_THRESHOLD * 100));
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private void runSkillRecallTest(ModelProvider provider, String targetSkillName, String prompt)
            throws IOException {
        String resultKey = "SKILL_RECALL/" + targetSkillName + "/" + provider.getProviderName();
        System.out.println(
                "\n>>> Skill Recall [" + targetSkillName + "] | " + provider.getProviderName());

        try (ClasspathSkillRepository repo = new ClasspathSkillRepository(SKILLS_CLASSPATH)) {

            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);

            for (String name : ALL_SKILL_NAMES) {
                AgentSkill skill = repo.getSkill(name);
                assertNotNull(skill, "Benchmark skill not found in classpath: " + name);
                skillBox.registration().skill(skill).apply();
            }
            skillBox.registerSkillLoadTool();

            AgentSkill targetSkill = repo.getSkill(targetSkillName);
            String expectedSkillId = targetSkill.getSkillId();
            System.out.println("    expected skillId : " + expectedSkillId);
            System.out.println("    prompt           : " + prompt);

            AtomicBoolean loadedCorrectSkill = new AtomicBoolean(false);

            ReActAgent agent =
                    provider.createAgentBuilder("SkillRecallAgent-" + targetSkillName, toolkit)
                            .maxIters(3)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PostActingEvent postActing) {
                                                ToolUseBlock toolUse = postActing.getToolUse();
                                                if (toolUse != null
                                                        && "load_skill_through_path"
                                                                .equals(toolUse.getName())
                                                        && expectedSkillId.equals(
                                                                toolUse.getInput()
                                                                        .get("skillId"))) {
                                                    loadedCorrectSkill.set(true);
                                                    postActing.stopAgent();
                                                }
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            try {
                agent.call(TestUtils.createUserMessage("User", prompt)).block(TIMEOUT);
            } catch (Exception e) {
                System.out.println("    agent error: " + e.getMessage());
            }

            boolean passed = loadedCorrectSkill.get();
            RESULTS.put(resultKey, passed);

            if (passed) {
                System.out.println(
                        "<<< [PASS] Skill recall ["
                                + targetSkillName
                                + "] | "
                                + provider.getProviderName());
            } else {
                System.out.println(
                        "<<< [FAIL] Skill recall ["
                                + targetSkillName
                                + "] | "
                                + provider.getProviderName()
                                + " — did not call load_skill_through_path(skillId='"
                                + expectedSkillId
                                + "')");
            }
        }
    }
}
