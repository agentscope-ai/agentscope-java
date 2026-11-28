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
package io.agentscope.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Behaviour spec for the {@link PermissionEngine}.
 *
 * <p>Engine-only behaviours (rule priority, mode fallbacks, default ASK/DENY) are exercised
 * directly against a {@link FakePermissionTool}.
 *
 * <p>Coverage targets:
 *
 * <ol>
 *   <li>Rule priority — deny &gt; ask &gt; allow
 *   <li>Modes — BYPASS / DONT_ASK / EXPLORE / DEFAULT
 *   <li>Tool-supplied decisions (ALLOW / DENY / ASK / PASSTHROUGH)
 *   <li>Default ASK with suggestions
 *   <li>Snapshot semantics
 * </ol>
 */
class PermissionEngineTest {

    /**
     * Minimal {@link ToolBase} used by engine-only tests. {@code checkPermissions} returns
     * PASSTHROUGH by default; tests may override via {@link #withPermissionDecision}.
     */
    private static final class FakePermissionTool extends ToolBase {

        private PermissionDecision toolDecision;

        FakePermissionTool(String name, boolean readOnly) {
            super(
                    name,
                    name + " description",
                    Map.of("type", "object", "properties", Map.of()),
                    /* isReadOnly */ readOnly,
                    /* isConcurrencySafe */ true,
                    /* isMcp */ false,
                    /* mcpName */ null,
                    /* isExternalTool */ false,
                    /* isStateInjected */ false);
        }

        FakePermissionTool withPermissionDecision(PermissionDecision decision) {
            this.toolDecision = decision;
            return this;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            if (toolDecision != null) {
                return Mono.just(toolDecision);
            }
            return Mono.just(PermissionDecision.passthrough("no tool-specific opinion"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new UnsupportedOperationException("not executed in engine tests"));
        }
    }

    private static PermissionRule allowAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "test");
    }

    private static PermissionRule denyAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.DENY, "test");
    }

    private static PermissionRule askAll(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ASK, "test");
    }

    private static PermissionContextState contextWithMode(PermissionMode mode) {
        return PermissionContextState.builder().mode(mode).build();
    }

    private static PermissionContextState contextWithWorkingDir(PermissionMode mode, String path) {
        return PermissionContextState.builder()
                .mode(mode)
                .addWorkingDirectory(path, new AdditionalWorkingDirectory(path, "userSettings"))
                .build();
    }

    // ==================== ACCEPT_EDITS working-directory consumption (#2870) ====================
    // Real temporary directories rather than a hard-coded path, so containment is checked
    // against a directory that actually exists (and whose real location may differ from the
    // lexical one, e.g. a symlinked temp root).

    @Test
    void acceptEdits_autoAllowsEditInsideWorkingDirectory(@TempDir Path root) throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", wd.resolve("notes.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    @Test
    void acceptEdits_editOutsideWorkingDirectory_stillAsks(@TempDir Path root) throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(
                                tool, Map.of("path", elsewhere.resolve("secret.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void acceptEdits_traversalOutOfWorkingDirectory_stillAsks(@TempDir Path root) throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        Path sibling = Files.createDirectories(root.resolve("escape"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(
                                tool, Map.of("path", wd.resolve("../escape/secret.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
        assertEquals(sibling, root.resolve("escape"));
    }

    @Test
    void acceptEdits_readOnlyInsideWorkingDirectory_stillAutoAllows(@TempDir Path root)
            throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool tool = new FakePermissionTool("read_file", true);

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", wd.resolve("a.md").toString())).block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    @Test
    void acceptEdits_explicitAllowRuleStillAppliesWhenTheToolAsks(@TempDir Path root)
            throws Exception {
        // Priority: an explicit allow rule outranks the tool's default opinion, which outranks
        // the working-directory auto-allow. The tool asked and the target is out of scope, yet
        // the user's rule decides.
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.ACCEPT_EDITS)
                                .addWorkingDirectory(
                                        wd.toString(),
                                        new AdditionalWorkingDirectory(
                                                wd.toString(), "userSettings"))
                                .addAllowRule("write_file", allowAll("write_file"))
                                .build());
        FakePermissionTool tool =
                new FakePermissionTool("write_file", false)
                        .withPermissionDecision(PermissionDecision.ask("confirm write"));

        PermissionDecision decision =
                engine.checkPermission(
                                tool, Map.of("path", root.resolve("elsewhere.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    @Test
    void acceptEdits_askingRuleStillAsksForAnInScopeTarget(@TempDir Path root) throws Exception {
        // An explicit ask rule outranks the working-directory auto-allow.
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.ACCEPT_EDITS)
                                .addWorkingDirectory(
                                        wd.toString(),
                                        new AdditionalWorkingDirectory(
                                                wd.toString(), "userSettings"))
                                .addAskRule("write_file", askAll("write_file"))
                                .build());
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", wd.resolve("notes.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    // ==================== ACCEPT_EDITS review boundaries (#3716) ====================
    // The engine cannot tell an edit tool from any other tool that happens to take a
    // path-shaped argument, and cannot resolve paths the way the executing tool will. So
    // the auto-allow must defer to the tool's own decision and must only ever apply to
    // absolute targets whose real location is provably inside a working directory.

    @Test
    void acceptEdits_doesNotOverrideToolDeny_evenForPathInsideWorkingDirectory(@TempDir Path root)
            throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool tool =
                new FakePermissionTool("custom_tool", false)
                        .withPermissionDecision(PermissionDecision.deny("hard deny"));

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", wd.resolve("a.md").toString())).block();

        assertEquals(PermissionBehavior.DENY, decision.getBehavior());
        assertEquals("hard deny", decision.getMessage());
    }

    @Test
    void acceptEdits_doesNotOverrideToolSafetyAsk_evenForPathInsideWorkingDirectory(
            @TempDir Path root) throws Exception {
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        // A non-edit tool that asks for a safety reason: the path-shaped argument must not
        // turn that into an ALLOW.
        FakePermissionTool tool =
                new FakePermissionTool("deploy", false)
                        .withPermissionDecision(PermissionDecision.ask("irreversible action"));

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", wd.resolve("a.md").toString())).block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
        assertEquals("irreversible action", decision.getMessage());
    }

    @Test
    void acceptEdits_relativeTargetFallsThroughToTheToolDecision(@TempDir Path root)
            throws Exception {
        // The engine does not know the tool's execution root, so a relative target cannot be
        // proven to land inside a working directory. It must not be auto-allowed.
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool asking =
                new FakePermissionTool("write_file", false)
                        .withPermissionDecision(PermissionDecision.ask("confirm write"));

        PermissionDecision decision =
                engine.checkPermission(asking, Map.of("path", "notes/idea.md")).block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void acceptEdits_relativeTargetWithoutToolOpinionFallsThroughToDefaultAsk(@TempDir Path root)
            throws Exception {
        // Same as above when the tool defers: the engine's default is ASK, not ALLOW.
        Path wd = Files.createDirectories(root.resolve("workspace"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, wd.toString()));
        FakePermissionTool deferring = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(deferring, Map.of("path", "notes/idea.md")).block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void acceptEdits_symlinkEscapeOutOfWorkingDirectory_stillAsks(@TempDir Path root)
            throws Exception {
        assumeTrue(isSymlinkSupported(root));
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.createSymbolicLink(allowed.resolve("link"), outside);

        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, allowed.toString()));
        // The tool defers on purpose: the outcome must depend only on where the target really
        // is. A tool that asks would yield ASK whatever the path check says, and so could not
        // tell a resolved symlink from a lexical prefix.
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        // Lexically inside `allowed`; really inside `outside`. A lexical prefix check would
        // auto-allow this write outside the working directory.
        PermissionDecision decision =
                engine.checkPermission(
                                tool, Map.of("path", allowed.resolve("link/new.txt").toString()))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void acceptEdits_symlinkEscapeViaNewFileInLinkedDirectory_stillAsks(@TempDir Path root)
            throws Exception {
        assumeTrue(isSymlinkSupported(root));
        // The same escape for a not-yet-existing file: its existing parent is the link, so the
        // real location is still outside.
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.createSymbolicLink(allowed.resolve("link"), outside);

        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, allowed.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(
                                tool,
                                Map.of(
                                        "path",
                                        allowed.resolve("link/new/deeper/file.txt").toString()))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void acceptEdits_symlinkedWorkingDirectoryItself_stillAutoAllows(@TempDir Path root)
            throws Exception {
        assumeTrue(isSymlinkSupported(root));
        // The configured directory may itself be reached through a link (temp roots often
        // are). Its real location is the base, so a direct write inside is still auto-allowed.
        Path real = Files.createDirectories(root.resolve("real-workspace"));
        Path viaLink = root.resolve("linked-workspace");
        Files.createSymbolicLink(viaLink, real);

        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, viaLink.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(tool, Map.of("path", real.resolve("notes.md").toString()))
                        .block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    @Test
    void acceptEdits_symlinkInsideWorkingDirectory_stillAutoAllows(@TempDir Path root)
            throws Exception {
        // A link that stays within the working directory must not be mistaken for an escape.
        assumeTrue(isSymlinkSupported(root));
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Files.createDirectories(allowed.resolve("real"));
        Files.createSymbolicLink(allowed.resolve("link"), allowed.resolve("real"));

        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, allowed.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        PermissionDecision decision =
                engine.checkPermission(
                                tool, Map.of("path", allowed.resolve("link/new.txt").toString()))
                        .block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    @Test
    void acceptEdits_newFileWhoseExistingParentIsInsideWorkingDirectory_autoAllows(
            @TempDir Path root) throws Exception {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Files.createDirectories(allowed.resolve("nested"));
        PermissionEngine engine =
                new PermissionEngine(
                        contextWithWorkingDir(PermissionMode.ACCEPT_EDITS, allowed.toString()));
        FakePermissionTool tool = new FakePermissionTool("write_file", false);

        // The leaf does not exist yet; its nearest existing ancestor decides.
        PermissionDecision decision =
                engine.checkPermission(
                                tool,
                                Map.of(
                                        "path",
                                        allowed.resolve("nested/new/deeper/file.txt").toString()))
                        .block();

        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
    }

    /** Creating symlinks needs a privilege Windows hosts do not grant by default. */
    private static boolean isSymlinkSupported(Path root) {
        try {
            Path probe = root.resolve("symlink-probe-target");
            Files.createDirectories(probe);
            Files.createSymbolicLink(root.resolve("symlink-probe"), probe);
            return true;
        } catch (Exception unsupported) {
            return false;
        }
    }

    private static PermissionRule rule(String tool, String content, PermissionBehavior behavior) {
        return new PermissionRule(tool, content, behavior, "test");
    }

    @Nested
    @DisplayName("Rule priority: deny > ask > allow")
    class RulePriority {

        @Test
        @DisplayName("Deny rule overrides allow rule on the same tool")
        void denyOverridesAllow() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("bash"));
            engine.addRule(denyAll("bash"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Ask rule overrides allow rule on the same tool")
        void askOverridesAllow() {
            FakePermissionTool tool = new FakePermissionTool("npm", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("npm"));
            engine.addRule(askAll("npm"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deny > Ask > Allow when all three are registered")
        void fullPriorityOrder() {
            FakePermissionTool tool = new FakePermissionTool("test", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("test"));
            engine.addRule(askAll("test"));
            engine.addRule(denyAll("test"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Modes: BYPASS / DONT_ASK / EXPLORE / DEFAULT")
    class Modes {

        @Test
        @DisplayName("BYPASS allows unmatched tool calls")
        void bypassAllowsByDefault() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("bypass"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deny rule wins even in BYPASS")
        void bypassRespectsDeny() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));
            engine.addRule(denyAll("bash"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.DENY, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("DONT_ASK converts default ASK into DENY")
        void dontAskDeniesUnknown() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine =
                    new PermissionEngine(contextWithMode(PermissionMode.DONT_ASK));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("dont_ask"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("EXPLORE allows read-only tools")
        void exploreAllowsRead() {
            FakePermissionTool tool = new FakePermissionTool("reader", /* readOnly */ true);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.EXPLORE));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("explore"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("EXPLORE denies non-read-only tools")
        void exploreDeniesWrite() {
            FakePermissionTool tool = new FakePermissionTool("writer", /* readOnly */ false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.EXPLORE));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertTrue(decision.getMessage().contains("explore"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Tool-supplied decisions")
    class ToolDecisions {

        @Test
        @DisplayName("Tool ALLOW short-circuits before allow rules")
        void toolAllowShortCircuits() {
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false)
                            .withPermissionDecision(PermissionDecision.allow("tool says yes"));
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertEquals("tool says yes", decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool DENY beats BYPASS")
        void toolDenyBeatsBypass() {
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false)
                            .withPermissionDecision(PermissionDecision.deny("tool blocked"));
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.DENY, decision.getBehavior());
                                assertEquals("tool blocked", decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool ASK with safety reason attaches suggestions and is bypass-immune")
        void toolSafetyAskBypassImmune() {
            PermissionDecision safetyAsk =
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message("safety check")
                            .decisionReason("Safety: dangerous arguments detected")
                            .build();
            FakePermissionTool tool =
                    new FakePermissionTool("custom", false).withPermissionDecision(safetyAsk);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.BYPASS));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertEquals(1, decision.getSuggestedRules().size());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Tool PASSTHROUGH falls through to allow rules / mode fallback")
        void toolPassthroughFallsThrough() {
            FakePermissionTool tool = new FakePermissionTool("custom", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("custom"));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Default ASK")
    class DefaultAsk {

        @Test
        @DisplayName("DEFAULT mode with no rules returns ASK with suggestions")
        void defaultAskWithSuggestions() {
            FakePermissionTool tool = new FakePermissionTool("anything", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));

            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertNotNull(decision.getSuggestedRules());
                                assertEquals(1, decision.getSuggestedRules().size());
                                PermissionRule suggested = decision.getSuggestedRules().get(0);
                                assertEquals("anything", suggested.toolName());
                                assertEquals(PermissionBehavior.ALLOW, suggested.behavior());
                                assertEquals("suggested", suggested.source());
                                assertNull(suggested.ruleContent());
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Engine snapshot semantics")
    class SnapshotSemantics {

        @Test
        @DisplayName("Engine rule tables are immutable views")
        void ruleTablesAreImmutable() {
            FakePermissionTool tool = new FakePermissionTool("bash", false);
            PermissionEngine engine = new PermissionEngine(contextWithMode(PermissionMode.DEFAULT));
            engine.addRule(allowAll("bash"));

            Map<String, List<PermissionRule>> snapshot = engine.getAllowRules();
            assertEquals(1, snapshot.size());
            assertEquals(1, snapshot.get("bash").size());

            // Adding more rules after the snapshot does not mutate the returned view.
            engine.addRule(allowAll("bash"));
            assertEquals(1, snapshot.get("bash").size());

            // Engine itself does see the new rule.
            StepVerifier.create(engine.checkPermission(tool, Map.of()))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Test
    void addRuleIgnoresExactDuplicates() {
        PermissionContextState context =
                PermissionContextState.builder()
                        .addAskRule(
                                "Bash",
                                new PermissionRule(
                                        "Bash", "rm -rf", PermissionBehavior.ASK, "test"))
                        .build();
        PermissionEngine engine = new PermissionEngine(context);

        PermissionRule accepted =
                new PermissionRule("Bash", null, PermissionBehavior.ALLOW, "user_confirm");
        engine.addRule(accepted);
        engine.addRule(accepted);
        engine.addRule(accepted);

        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                engine.getAllowRules().get("Bash").size(),
                "identical re-accept must not grow the engine table");
    }

    @Test
    void addRuleTreatsNullAndEmptyContentAsEquivalent() {
        // ruleMatches evaluates null and empty ruleContent identically
        // (both unconditional), so the engine table must not hold both.
        PermissionContextState context =
                PermissionContextState.builder()
                        .addAskRule(
                                "Bash",
                                new PermissionRule(
                                        "Bash", "rm -rf", PermissionBehavior.ASK, "test"))
                        .build();
        PermissionEngine engine = new PermissionEngine(context);

        engine.addRule(new PermissionRule("Bash", null, PermissionBehavior.ALLOW, "user_confirm"));
        engine.addRule(new PermissionRule("Bash", "", PermissionBehavior.ALLOW, "user_confirm"));

        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                engine.getAllowRules().get("Bash").size(),
                "null and empty ruleContent are the same rule at evaluation time");
    }
}
