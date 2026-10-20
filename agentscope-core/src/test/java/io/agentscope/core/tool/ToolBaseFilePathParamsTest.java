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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

/**
 * Unit spec for the path-aware default {@code checkPermissions} unlocked by {@code
 * filePathParams}.
 *
 * <p>Evaluation order under test:
 *
 * <ol>
 *   <li>Extraction — missing / blank declared param → PASSTHROUGH; non-string value →
 *       bypass-immune Safety-ASK
 *   <li>ANY declared path dangerous (or unresolvable) → Safety-ASK
 *   <li>ACCEPT_EDITS + ALL paths provably inside a working directory → ALLOW (execution landing
 *       point comes from the tool's resolver, never guessed; symlinks incl. dangling ones are
 *       resolved; cycles/unreadable links fail closed)
 *   <li>Anything else → PASSTHROUGH (engine rule tables decide)
 * </ol>
 */
class ToolBaseFilePathParamsTest {

    /** Minimal tool with configurable declared path parameters and no path resolver. */
    private static final class FakeFileTool extends ToolBase {
        FakeFileTool(String name, Set<String> params) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name + " description")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .filePathParams(params));
        }
    }

    /** Tool that resolves relative paths against a fixed base directory (execution mirror). */
    private static final class ResolvingFakeFileTool extends ToolBase {
        private final Path baseDir;

        ResolvingFakeFileTool(String name, Set<String> params, Path baseDir) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name + " description")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .filePathParams(params));
            this.baseDir = baseDir;
        }

        @Override
        protected Optional<Path> resolveExecutionPath(String rawPath) {
            if (rawPath == null || rawPath.isBlank()) {
                return Optional.empty();
            }
            Path p = Path.of(rawPath);
            return Optional.of((p.isAbsolute() ? p : baseDir.resolve(p)).normalize());
        }
    }

    /** Tool whose resolver throws, to verify the fail-closed exception handling. */
    private static final class ThrowingFakeFileTool extends ToolBase {
        ThrowingFakeFileTool(String name, Set<String> params) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name + " description")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .filePathParams(params));
        }

        @Override
        protected Optional<Path> resolveExecutionPath(String rawPath) {
            throw new IllegalStateException("resolver exploded");
        }
    }

    private static PermissionContextState acceptEditsWithDir(String dir) {
        return PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory(dir, new AdditionalWorkingDirectory(dir, "test"))
                .build();
    }

    private static Map<String, Object> inputWithPath(String path) {
        return Map.of("path", path);
    }

    @Nested
    @DisplayName("ACCEPT_EDITS working-directory auto-allow")
    class AcceptEditsWorkingDir {

        @Test
        @DisplayName("absolute path inside the working directory → ALLOW")
        void absolutePathInsideWorkingDir(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve("src/Main.java").toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());
                                assertEquals(
                                        "Permission granted (accept edits mode, in working"
                                                + " directory)",
                                        decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("relative path without a resolver is not auto-allowed → PASSTHROUGH")
        void relativePathWithoutResolverNotAutoAllowed(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath("src/Main.java"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("relative path with a resolver anchored inside the scope → ALLOW")
        void resolverEnablesRelativeAutoAllow(@TempDir Path workDir) {
            ResolvingFakeFileTool tool =
                    new ResolvingFakeFileTool("write_file", Set.of("path"), workDir);

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath("src/Main.java"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("path outside every working directory → PASSTHROUGH")
        void pathOutsideWorkingDir(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath("/etc/passwd"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("no working directories configured → PASSTHROUGH")
        void noWorkingDirectoriesConfigured() {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));
            PermissionContextState ctx =
                    PermissionContextState.builder().mode(PermissionMode.ACCEPT_EDITS).build();

            StepVerifier.create(tool.checkPermissions(inputWithPath("/tmp/notes.txt"), ctx))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("dotdot traversal out of the working directory → PASSTHROUGH")
        void dotDotEscapeIsNotAutoAllowed(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir + "/../../../etc/passwd"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Multi-path tools (e.g. copy src/dst)")
    class MultiPath {

        private final FakeFileTool copyTool = new FakeFileTool("copy_file", Set.of("src", "dst"));

        @Test
        @DisplayName("all declared paths inside the working directory → ALLOW")
        void allPathsInsideScopeAllow(@TempDir Path workDir) {
            Map<String, Object> input =
                    Map.of(
                            "src", workDir.resolve("a.txt").toString(),
                            "dst", workDir.resolve("b.txt").toString());

            StepVerifier.create(
                            copyTool.checkPermissions(
                                    input, acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("any path outside the working directory → PASSTHROUGH")
        void onePathOutsideScopePassthrough(@TempDir Path workDir) {
            Map<String, Object> input =
                    Map.of("src", workDir.resolve("a.txt").toString(), "dst", "/etc/evil.txt");

            StepVerifier.create(
                            copyTool.checkPermissions(
                                    input, acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("any dangerous path wins over the in-scope ALLOW → Safety-ASK")
        void anyDangerousPathSafetyAsks(@TempDir Path workDir) {
            Map<String, Object> input =
                    Map.of(
                            "src", workDir.resolve(".bashrc").toString(),
                            "dst", workDir.resolve("b.txt").toString());

            StepVerifier.create(
                            copyTool.checkPermissions(
                                    input, acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: dangerous file or directory",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName(
                "missing declared param never auto-allows on the remaining subset → PASSTHROUGH")
        void missingDeclaredParamIsNotAutoAllowed(@TempDir Path workDir) {
            Map<String, Object> input = Map.of("src", workDir.resolve("a.txt").toString());

            StepVerifier.create(
                            copyTool.checkPermissions(
                                    input, acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Symlink resolution")
    class SymlinkResolution {

        @Test
        @DisplayName("existing file via symlink pointing outside the scope → PASSTHROUGH")
        void existingFileThroughSymlinkOutsideScope(@TempDir Path workDir, @TempDir Path outsideDir)
                throws IOException {
            Path target = Files.writeString(outsideDir.resolve("secret.txt"), "s");
            Path link = workDir.resolve("escape-link");
            Files.createSymbolicLink(link, target);
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("new file inside a symlinked dir pointing outside → PASSTHROUGH")
        void newFileThroughSymlinkedDirOutsideScope(@TempDir Path workDir, @TempDir Path outsideDir)
                throws IOException {
            Path link = workDir.resolve("escape-link");
            Files.createSymbolicLink(link, outsideDir);
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.resolve("new-file.txt").toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName(
                "dangling symlink pointing outside is expanded, not treated as a filename →"
                        + " PASSTHROUGH")
        void danglingSymlinkToOutsideIsNotAutoAllowed(
                @TempDir Path workDir, @TempDir Path outsideDir) throws IOException {
            Path link = workDir.resolve("escape-link");
            Files.createSymbolicLink(link, outsideDir.resolve("not-yet-created.txt"));
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName(
                "dangling symlink pointing inside still auto-allows (kernel semantics) → ALLOW")
        void danglingSymlinkToInsideStillAllowed(@TempDir Path workDir) throws IOException {
            Path link = workDir.resolve("pending-link");
            Files.createSymbolicLink(link, workDir.resolve("not-yet-created.txt"));
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("dangling symlink whose target is a dangerous file → Safety-ASK")
        void danglingSymlinkToDangerousFileSafetyAsks(@TempDir Path workDir) throws IOException {
            Path link = workDir.resolve("rc-link");
            Files.createSymbolicLink(link, workDir.resolve(".bashrc"));
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: dangerous file or directory",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("cyclic symlink chain fails closed as Safety-ASK")
        void cyclicSymlinkIsSafetyAsk(@TempDir Path workDir) throws IOException {
            Path a = workDir.resolve("link-a");
            Path b = workDir.resolve("link-b");
            Files.createSymbolicLink(a, b);
            Files.createSymbolicLink(b, a);
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(a.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("chained symlinks resolving outside → PASSTHROUGH")
        void chainedSymlinkToOutsidePassthrough(@TempDir Path workDir, @TempDir Path outsideDir)
                throws IOException {
            Path l2 = workDir.resolve("link-2");
            Files.createSymbolicLink(l2, outsideDir.resolve("target.txt"));
            Path l1 = workDir.resolve("link-1");
            Files.createSymbolicLink(l1, l2);
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(l1.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("relative link target resolves against the link's parent directory")
        void relativeTargetSymlinkResolvesAgainstLinkParent(
                @TempDir Path workDir, @TempDir Path outsideDir) throws IOException {
            Path link = workDir.resolve("rel-link");
            Files.createSymbolicLink(
                    link, Path.of("..", outsideDir.getFileName().toString(), "target.txt"));
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(link.toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("working directory configured via symlink still matches real target → ALLOW")
        void workingDirConfiguredViaSymlink(@TempDir Path realDir, @TempDir Path linkParent)
                throws IOException {
            Path link = linkParent.resolve("work-link");
            Files.createSymbolicLink(link, realDir);
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(realDir.resolve("a.txt").toString()),
                                    acceptEditsWithDir(link.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Tilde expansion")
    class TildeExpansion {

        @Test
        @DisplayName("~/file with home as working directory → ALLOW")
        void tildePathAgainstHomeWorkingDir() {
            String home = System.getProperty("user.home");
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath("~/agentscope-tilde-probe.txt"),
                                    acceptEditsWithDir(home)))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("working directory declared as ~ expands to home → ALLOW")
        void workingDirDeclaredAsTilde() {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(
                                            System.getProperty("user.home")
                                                    + "/agentscope-tilde-probe.txt"),
                                    acceptEditsWithDir("~")))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ALLOW, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Dangerous path check")
    class DangerousPath {

        @Test
        @DisplayName("ACCEPT_EDITS + .bashrc inside the working directory → Safety-ASK")
        void acceptEditsDangerousPathAsks(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve(".bashrc").toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: dangerous file or directory",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("BYPASS + .bashrc still Safety-ASKs (bypass-immune)")
        void bypassDangerousPathStillAsks(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));
            PermissionContextState ctx =
                    PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .addWorkingDirectory(
                                    workDir.toString(),
                                    new AdditionalWorkingDirectory(workDir.toString(), "test"))
                            .build();

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve(".bashrc").toString()), ctx))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("resolver-anchored dangerous landing catches relative arguments → Safety-ASK")
        void resolverAnchoredDangerousLandingSafetyAsks(@TempDir Path sshProj) throws IOException {
            Path sshDir = sshProj.resolve(".ssh");
            Files.createDirectories(sshDir);
            ResolvingFakeFileTool tool =
                    new ResolvingFakeFileTool("write_file", Set.of("path"), sshProj);

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(".ssh/config"),
                                    acceptEditsWithDir(sshProj.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: dangerous file or directory",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Mode isolation")
    class ModeIsolation {

        @Test
        @DisplayName("DEFAULT mode never auto-allows even inside the working directory")
        void defaultModeDoesNotAutoAllow(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));
            PermissionContextState ctx =
                    PermissionContextState.builder()
                            .mode(PermissionMode.DEFAULT)
                            .addWorkingDirectory(
                                    workDir.toString(),
                                    new AdditionalWorkingDirectory(workDir.toString(), "test"))
                            .build();

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve("a.txt").toString()), ctx))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("EXPLORE-mode decisions stay with the engine; tool self-check passes through")
        void exploreToolCheckPassthrough(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));
            PermissionContextState ctx =
                    PermissionContextState.builder()
                            .mode(PermissionMode.EXPLORE)
                            .addWorkingDirectory(
                                    workDir.toString(),
                                    new AdditionalWorkingDirectory(workDir.toString(), "test"))
                            .build();

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve("a.txt").toString()), ctx))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Path extraction strictness")
    class PathExtraction {

        @Test
        @DisplayName("blank declared param → PASSTHROUGH")
        void blankStringPassthrough(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("path", "   "), acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("non-string scalar declared param → Safety-ASK")
        void nonStringScalarSafetyAsks(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("path", 42), acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: unverifiable path parameter",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("list with mixed types → Safety-ASK")
        void listMixedTypesSafetyAsks(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("path", List.of("ok.txt", 7)),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("NUL byte in path does not crash, fails closed as Safety-ASK")
        void nulBytePathDoesNotThrow(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("path", "bad\0path"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(PermissionBehavior.ASK, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("unexpected resolver exception fails closed as Safety-ASK")
        void unexpectedExceptionSafetyAsks(@TempDir Path workDir) {
            ThrowingFakeFileTool tool = new ThrowingFakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve("a.txt").toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(PermissionBehavior.ASK, decision.getBehavior());
                                assertEquals(
                                        "Safety check: path check failed",
                                        decision.getDecisionReason());
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompat {

        @Test
        @DisplayName("tool without declared filePathParams keeps plain PASSTHROUGH")
        void undeclaredParamsStayPassthrough(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of());

            StepVerifier.create(
                            tool.checkPermissions(
                                    inputWithPath(workDir.resolve(".bashrc").toString()),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(
                                        PermissionBehavior.PASSTHROUGH, decision.getBehavior());
                                assertEquals("write_file", decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("declared param missing from the input → PASSTHROUGH")
        void declaredParamMissingFromInput(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("content", "hello"),
                                    acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision -> {
                                assertEquals(
                                        PermissionBehavior.PASSTHROUGH, decision.getBehavior());
                                assertEquals(
                                        "write_file missing filePath param: path",
                                        decision.getMessage());
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("null tool input → PASSTHROUGH")
        void nullToolInput(@TempDir Path workDir) {
            FakeFileTool tool = new FakeFileTool("write_file", Set.of("path"));

            StepVerifier.create(tool.checkPermissions(null, acceptEditsWithDir(workDir.toString())))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("duplicate declarations collapse preserving order")
        void duplicateDeclarationsCollapse() {
            FakeFileTool tool =
                    new FakeFileTool(
                            "copy_file", new LinkedHashSet<>(List.of("src", "src", "dst")));

            assertEquals(List.of("src", "dst"), List.copyOf(tool.getFilePathParams()));
        }

        @Test
        @DisplayName("positional nine-arg constructor defaults to no declared paths")
        void positionalConstructorDefaultsEmpty() {
            ToolBase tool =
                    new ToolBase(
                            "legacy",
                            "legacy description",
                            Map.of("type", "object", "properties", Map.of()),
                            /* readOnly */ false,
                            /* concurrencySafe */ true,
                            /* mcp */ false,
                            /* mcpName */ null,
                            /* externalTool */ false,
                            /* stateInjected */ false) {};

            assertEquals(Set.of(), tool.getFilePathParams());
            StepVerifier.create(
                            tool.checkPermissions(
                                    Map.of("path", "/tmp/a.txt"),
                                    PermissionContextState.builder()
                                            .mode(PermissionMode.ACCEPT_EDITS)
                                            .build()))
                    .assertNext(
                            decision ->
                                    assertEquals(
                                            PermissionBehavior.PASSTHROUGH, decision.getBehavior()))
                    .verifyComplete();
        }
    }
}
