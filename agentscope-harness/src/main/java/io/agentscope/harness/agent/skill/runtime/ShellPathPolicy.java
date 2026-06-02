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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.nio.file.Path;

/**
 * Resolves the absolute {@code filesRoot} path emitted in {@code <available_skills>} and
 * {@code load_skill_through_path} responses, given a skill's {@link StageResult} and the
 * current shell mode.
 *
 * <p>Three modes the policy knows about:
 *
 * <ul>
 *   <li>{@link Mode#NO_SHELL} — no {@code ShellExecuteTool} registered. Returns {@code null}
 *       for every skill so the prompt omits {@code <files-root>} and the code-execution
 *       instruction block entirely.
 *   <li>{@link Mode#SANDBOX} — sandbox-backed filesystem. Paths use the sandbox-internal
 *       prefix ({@code /workspace/}).
 *   <li>{@link Mode#LOCAL_WITH_SHELL} — {@code LocalFilesystemWithShell}. Paths use the
 *       absolute host workspace root.
 * </ul>
 */
public final class ShellPathPolicy {

    public enum Mode {
        NO_SHELL,
        SANDBOX,
        LOCAL_WITH_SHELL
    }

    public static final String SANDBOX_WORKSPACE_PREFIX = "/workspace";

    private final Mode mode;
    private final Path workspaceRoot;

    private ShellPathPolicy(Mode mode, Path workspaceRoot) {
        this.mode = mode;
        this.workspaceRoot = workspaceRoot;
    }

    /** No shell is available; every {@code filesRoot} resolves to {@code null}. */
    public static ShellPathPolicy noShell() {
        return new ShellPathPolicy(Mode.NO_SHELL, null);
    }

    /** Sandbox mode — paths under {@code /workspace/}. */
    public static ShellPathPolicy sandbox() {
        return new ShellPathPolicy(Mode.SANDBOX, null);
    }

    /** Local-with-shell mode — paths absolute on the host. */
    public static ShellPathPolicy localWithShell(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot required for LOCAL_WITH_SHELL");
        }
        return new ShellPathPolicy(Mode.LOCAL_WITH_SHELL, workspaceRoot);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Returns the absolute {@code filesRoot} for the given skill, or {@code null} when shell
     * is unavailable / the skill has no shell-reachable representation.
     *
     * @param skillName the skill's {@code name}
     * @param stage     staging outcome from {@link MarketplaceStager#stage}
     */
    public String resolve(String skillName, StageResult stage) {
        if (mode == Mode.NO_SHELL || stage == null || stage instanceof StageResult.None) {
            return null;
        }
        if (stage instanceof StageResult.WorkspaceNative) {
            return joinSkills(skillName);
        }
        if (stage instanceof StageResult.Cached cached) {
            return joinCache(cached.sourceNamespace(), cached.skillName());
        }
        return null;
    }

    private String joinSkills(String skillName) {
        return switch (mode) {
            case SANDBOX -> SANDBOX_WORKSPACE_PREFIX + "/skills/" + skillName;
            case LOCAL_WITH_SHELL ->
                    workspaceRoot.resolve("skills").resolve(skillName).toAbsolutePath().toString();
            case NO_SHELL -> null;
        };
    }

    private String joinCache(String sourceNs, String skillName) {
        return switch (mode) {
            case SANDBOX ->
                    SANDBOX_WORKSPACE_PREFIX
                            + "/"
                            + MarketplaceStager.CACHE_DIR
                            + "/"
                            + sourceNs
                            + "/"
                            + skillName;
            case LOCAL_WITH_SHELL ->
                    workspaceRoot
                            .resolve(MarketplaceStager.CACHE_DIR)
                            .resolve(sourceNs)
                            .resolve(skillName)
                            .toAbsolutePath()
                            .toString();
            case NO_SHELL -> null;
        };
    }
}
