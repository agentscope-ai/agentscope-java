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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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

    // key = "SKILL_RECALL/<skillName>/<provider>" or "CODE_EXEC/<skillName>/<provider>"
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
    // Code execution recall benchmark scenarios
    //
    // Each test loads ONE skill, uploads its scripts to a temp dir, then verifies
    // the LLM references the deployed script path in a shell command.
    // -------------------------------------------------------------------------

    /** Code execution recall: LLM must reference the deployed extract_errors.py script. */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[code-exec] log-parser: run extract_errors.py on a log file")
    void testCodeExecRecall_logParser(ModelProvider provider, @TempDir Path tempDir)
            throws IOException {
        runCodeExecutionRecallTest(
                provider,
                "log-parser",
                "scripts/extract_errors.py",
                "I have a server log file at /var/log/app.log that's 500 MB."
                        + " I need to extract all ERROR and WARN entries with their"
                        + " timestamps into a structured format.",
                tempDir);
    }

    /** Code execution recall: LLM must reference the deployed generate_changelog.py script. */
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("[code-exec] git-changelog: run generate_changelog.py between two tags")
    void testCodeExecRecall_gitChangelog(ModelProvider provider, @TempDir Path tempDir)
            throws IOException {
        runCodeExecutionRecallTest(
                provider,
                "git-changelog",
                "scripts/generate_changelog.py",
                "I need to produce a Markdown changelog for our upcoming release,"
                        + " covering all commits between the v1.5.0 and HEAD tags.",
                tempDir);
    }

    // -------------------------------------------------------------------------
    // Aggregate recall-rate assertion (runs once after all tests complete)
    // -------------------------------------------------------------------------

    @AfterAll
    static void assertRecallRates() {
        if (RESULTS.isEmpty()) {
            return;
        }

        long skillTotal =
                RESULTS.keySet().stream().filter(k -> k.startsWith("SKILL_RECALL")).count();
        long skillHits =
                RESULTS.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("SKILL_RECALL") && e.getValue())
                        .count();

        long codeTotal = RESULTS.keySet().stream().filter(k -> k.startsWith("CODE_EXEC")).count();
        long codeHits =
                RESULTS.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("CODE_EXEC") && e.getValue())
                        .count();

        long totalRuns = skillTotal + codeTotal;
        long totalHits = skillHits + codeHits;

        double skillRate = skillTotal > 0 ? (double) skillHits / skillTotal : 1.0;
        double codeRate = codeTotal > 0 ? (double) codeHits / codeTotal : 1.0;
        double overallRate = totalRuns > 0 ? (double) totalHits / totalRuns : 1.0;

        String sep = "=".repeat(62);
        System.out.println("\n" + sep);
        System.out.println("  SKILL BENCHMARK RECALL SUMMARY");
        System.out.println(sep);

        System.out.println("\n  Skill recall:");
        RESULTS.entrySet().stream()
                .filter(e -> e.getKey().startsWith("SKILL_RECALL"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            String label = e.getKey().substring("SKILL_RECALL/".length());
                            System.out.printf(
                                    "    %s  %s%n", e.getValue() ? "[PASS]" : "[FAIL]", label);
                        });

        System.out.println("\n  Code execution recall:");
        RESULTS.entrySet().stream()
                .filter(e -> e.getKey().startsWith("CODE_EXEC"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e -> {
                            String label = e.getKey().substring("CODE_EXEC/".length());
                            System.out.printf(
                                    "    %s  %s%n", e.getValue() ? "[PASS]" : "[FAIL]", label);
                        });

        System.out.println();
        System.out.printf(
                "  Skill recall:          %d / %d  (%.0f%%)%n",
                skillHits, skillTotal, skillRate * 100);
        System.out.printf(
                "  Code execution recall: %d / %d  (%.0f%%)%n",
                codeHits, codeTotal, codeRate * 100);
        System.out.printf(
                "  Overall:               %d / %d  (%.0f%%)   threshold >= %.0f%%%n",
                totalHits, totalRuns, overallRate * 100, RECALL_THRESHOLD * 100);
        System.out.println(sep + "\n");

        assertTrue(
                overallRate >= RECALL_THRESHOLD,
                String.format(
                        "Recall rate %.0f%% (%d/%d) is below the %.0f%% threshold."
                                + " skill=%.0f%% (%d/%d), code=%.0f%% (%d/%d)",
                        overallRate * 100,
                        totalHits,
                        totalRuns,
                        RECALL_THRESHOLD * 100,
                        skillRate * 100,
                        skillHits,
                        skillTotal,
                        codeRate * 100,
                        codeHits,
                        codeTotal));
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
                            .memory(new InMemoryMemory())
                            .maxIters(3)
                            .skillBox(skillBox)
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

    private void runCodeExecutionRecallTest(
            ModelProvider provider,
            String skillName,
            String expectedScriptRelativePath,
            String prompt,
            Path tempDir)
            throws IOException {
        String resultKey = "CODE_EXEC/" + skillName + "/" + provider.getProviderName();
        System.out.println(
                "\n>>> Code Execution Recall [" + skillName + "] | " + provider.getProviderName());

        try (ClasspathSkillRepository repo = new ClasspathSkillRepository(SKILLS_CLASSPATH)) {

            AgentSkill skill = repo.getSkill(skillName);
            assertNotNull(skill, "Benchmark skill not found in classpath: " + skillName);

            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            skillBox.registration().skill(skill).apply();
            skillBox.registerSkillLoadTool();

            String expectedScriptAbsPath =
                    tempDir.resolve("skills")
                            .resolve(skill.getSkillId())
                            .resolve(expectedScriptRelativePath)
                            .toAbsolutePath()
                            .toString();
            System.out.println("    expected script  : " + expectedScriptAbsPath);
            System.out.println("    prompt           : " + prompt);

            AtomicReference<String> capturedCommand = new AtomicReference<>("<none>");
            AtomicBoolean calledCorrectScript = new AtomicBoolean(false);

            ShellCommandTool interceptingShell =
                    new ShellCommandTool(
                            null,
                            Set.of("ls"),
                            command -> {
                                capturedCommand.set(command);
                                if (command.contains(expectedScriptAbsPath)) {
                                    calledCorrectScript.set(true);
                                }
                                return false;
                            });

            skillBox.codeExecution()
                    .workDir(tempDir.toString())
                    .withShell(interceptingShell)
                    .enable();

            Hook earlyExitHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PostActingEvent postActing
                                    && calledCorrectScript.get()) {
                                postActing.stopAgent();
                            }
                            return Mono.just(event);
                        }
                    };

            ReActAgent agent =
                    provider.createAgentBuilder("CodeExecAgent-" + skillName, toolkit)
                            .memory(new InMemoryMemory())
                            .maxIters(6)
                            .skillBox(skillBox)
                            .hook(earlyExitHook)
                            .build();

            agent.call(TestUtils.createUserMessage("User", prompt)).block(TIMEOUT);

            // Print tool call trace for diagnosis
            agent.getMemory().getMessages().stream()
                    .filter(msg -> msg.getRole() == MsgRole.ASSISTANT)
                    .filter(msg -> msg.hasContentBlocks(ToolUseBlock.class))
                    .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                    .forEach(
                            tb ->
                                    System.out.println(
                                            "    tool: " + tb.getName() + "  " + tb.getInput()));

            boolean passed = calledCorrectScript.get();
            RESULTS.put(resultKey, passed);

            if (passed) {
                System.out.println(
                        "<<< [PASS] Code execution recall ["
                                + skillName
                                + "] | "
                                + provider.getProviderName());
            } else {
                System.out.println(
                        "<<< [FAIL] Code execution recall ["
                                + skillName
                                + "] | "
                                + provider.getProviderName()
                                + " — last command: '"
                                + capturedCommand.get()
                                + "'");
            }
        }
    }
}
