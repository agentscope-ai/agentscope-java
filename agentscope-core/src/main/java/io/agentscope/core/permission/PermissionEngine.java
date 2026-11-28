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

import io.agentscope.core.tool.ToolBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Evaluates tool execution requests against configured permission rules.
 *
 * <p>Evaluation order:
 *
 * <ol>
 *   <li>Tool-level deny rules (highest priority).
 *   <li>Tool-level ask rules.
 *   <li>Tool-specific checks (bypass-immune): EXPLORE/ACCEPT_EDITS read-only handling, dangerous
 *       path checks, plus whatever the tool's own {@link ToolBase#checkPermissions} returns.
 *   <li>Tool-level allow rules.
 *   <li>{@link PermissionMode#BYPASS} fallback.
 *   <li>Default ASK (converted to DENY under {@link PermissionMode#DONT_ASK}).
 * </ol>
 *
 * <p>The engine snapshots rules from the supplied {@link PermissionContextState} into its own mutable
 * tables on construction; the original context is never mutated. Use {@link #addRule} to extend
 * the engine's rule set at runtime.
 */
public final class PermissionEngine {

    private final PermissionContextState context;
    private final Map<String, List<PermissionRule>> allowRules;
    private final Map<String, List<PermissionRule>> denyRules;
    private final Map<String, List<PermissionRule>> askRules;

    /**
     * Creates an engine seeded from the given context's rules and mode.
     *
     * @param context permission context providing mode, working directories, and initial rules
     */
    public PermissionEngine(PermissionContextState context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.allowRules = copyMutable(context.getAllowRules());
        this.denyRules = copyMutable(context.getDenyRules());
        this.askRules = copyMutable(context.getAskRules());
    }

    private static Map<String, List<PermissionRule>> copyMutable(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> out = new HashMap<>();
        if (source != null) {
            for (Map.Entry<String, List<PermissionRule>> e : source.entrySet()) {
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        return out;
    }

    /**
     * Returns the underlying context (mode and working directories are read from it).
     *
     * @return the immutable context this engine was constructed with
     */
    public PermissionContextState getContext() {
        return context;
    }

    /**
     * Adds a rule to the engine's internal rule set.
     *
     * <p>The rule is routed by its {@link PermissionRule#behavior()}: ALLOW/DENY/ASK rules are
     * appended to the engine's allow/deny/ask tables; PASSTHROUGH rules are ignored.
     *
     * <p>An equivalent rule already in the target table is not re-added — equivalence treats a
     * {@code null} and an empty {@link PermissionRule#ruleContent()} as identical, matching how
     * {@code ruleMatches} evaluates both as unconditional. Re-accepting the same confirmation
     * therefore never grows the tables.
     *
     * @param rule the rule to add; must be non-null
     */
    public void addRule(PermissionRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        switch (rule.behavior()) {
            case ALLOW -> PermissionContextState.addIfAbsent(allowRules, rule);
            case DENY -> PermissionContextState.addIfAbsent(denyRules, rule);
            case ASK -> PermissionContextState.addIfAbsent(askRules, rule);
            case PASSTHROUGH -> {
                // PASSTHROUGH rules are not stored; they signal "defer to engine".
            }
        }
    }

    /** Read-only view of the engine's current allow-rule table. */
    public Map<String, List<PermissionRule>> getAllowRules() {
        return unmodifiableSnapshot(allowRules);
    }

    /** Read-only view of the engine's current deny-rule table. */
    public Map<String, List<PermissionRule>> getDenyRules() {
        return unmodifiableSnapshot(denyRules);
    }

    /** Read-only view of the engine's current ask-rule table. */
    public Map<String, List<PermissionRule>> getAskRules() {
        return unmodifiableSnapshot(askRules);
    }

    private static Map<String, List<PermissionRule>> unmodifiableSnapshot(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> snapshot = new HashMap<>();
        for (Map.Entry<String, List<PermissionRule>> e : source.entrySet()) {
            snapshot.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Resolves a permission decision for the given tool invocation.
     *
     * @param tool the tool being called
     * @param toolInput the input map the tool will receive
     * @return a Mono emitting the resolved {@link PermissionDecision}
     */
    public Mono<PermissionDecision> checkPermission(ToolBase tool, Map<String, Object> toolInput) {
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> input = toolInput == null ? Map.of() : toolInput;

        // 1. Deny rules (highest priority)
        PermissionDecision denyDecision = checkDenyRules(tool, input);
        if (denyDecision != null) {
            return Mono.just(denyDecision);
        }

        // 2. Ask rules
        PermissionDecision askDecision = checkAskRules(tool, input);
        if (askDecision != null) {
            return Mono.just(askDecision.withSuggestedRules(tool.generateSuggestions(input)));
        }

        // 3. Tool-specific check (bypass-immune)
        return toolCheckPermissions(tool, input)
                .flatMap(
                        toolDecision -> {
                            if (toolDecision.getBehavior() == PermissionBehavior.DENY) {
                                return Mono.just(toolDecision);
                            }
                            if (toolDecision.getBehavior() == PermissionBehavior.ALLOW) {
                                return Mono.just(toolDecision);
                            }
                            if (toolDecision.getBehavior() == PermissionBehavior.ASK
                                    && toolDecision.getDecisionReason() != null
                                    && toolDecision
                                            .getDecisionReason()
                                            .toLowerCase(Locale.ROOT)
                                            .contains("safety")) {
                                return Mono.just(
                                        toolDecision.withSuggestedRules(
                                                tool.generateSuggestions(input)));
                            }
                            // A tool that asks is never answered by the ACCEPT_EDITS
                            // working-directory auto-allow: the engine cannot tell which ASK
                            // is safety-relevant. Explicit allow rules still apply, and the
                            // tool's own reason for asking is what the user gets to see
                            // (#3716).
                            if (context.getMode() == PermissionMode.ACCEPT_EDITS) {
                                return continueAfterToolCheck(tool, input, toolDecision);
                            }
                            return continueAfterToolCheck(tool, input, null);
                        })
                .switchIfEmpty(Mono.defer(() -> continueAfterToolCheck(tool, input, null)));
    }

    /**
     * @param toolAsk the tool's own ASK decision under ACCEPT_EDITS, or {@code null}. When
     *     present the working-directory auto-allow is skipped and, if no explicit rule decides,
     *     this decision — carrying the tool's reason — is returned instead of the generic
     *     default.
     */
    private Mono<PermissionDecision> continueAfterToolCheck(
            ToolBase tool, Map<String, Object> input, PermissionDecision toolAsk) {
        // 4. Allow rules
        PermissionDecision allowDecision = checkAllowRules(tool, input);
        if (allowDecision != null) {
            return Mono.just(allowDecision);
        }

        // 5. Mode fallbacks. ACCEPT_EDITS: a write inside a configured working directory is
        // approved without asking — but never over a tool that asked. Placed after explicit
        // rules and the tool's own decision so neither is overridden (#3716).
        PermissionDecision inWorkingDirectory =
                toolAsk == null ? acceptEditsInWorkingDirectory(tool, input) : null;
        if (inWorkingDirectory != null) {
            return Mono.just(inWorkingDirectory);
        }

        // 6. BYPASS fallback
        if (context.getMode() == PermissionMode.BYPASS) {
            return Mono.just(
                    PermissionDecision.builder()
                            .behavior(PermissionBehavior.ALLOW)
                            .message("Permission granted for " + tool.getName() + " (bypass mode)")
                            .decisionReason("Bypass mode allows all operations")
                            .build());
        }

        // 7. Default (ASK, or DENY under DONT_ASK)
        if (toolAsk != null) {
            return Mono.just(toolAsk.withSuggestedRules(tool.generateSuggestions(input)));
        }
        return Mono.just(
                defaultDecisionAsk(tool.getName())
                        .withSuggestedRules(tool.generateSuggestions(input)));
    }

    /**
     * Runs the tool-specific permission pipeline.
     *
     * <p>EXPLORE / ACCEPT_EDITS read-only handling first, then the tool's own
     * {@link ToolBase#checkPermissions}. Emits empty when the tool returns PASSTHROUGH.
     */
    private Mono<PermissionDecision> toolCheckPermissions(
            ToolBase tool, Map<String, Object> input) {
        if (context.getMode() == PermissionMode.EXPLORE
                || context.getMode() == PermissionMode.ACCEPT_EDITS) {
            PermissionDecision modeDecision = checkExploreMode(tool);
            if (modeDecision != null) {
                return Mono.just(modeDecision);
            }
        }
        return tool.checkPermissions(input, context)
                .flatMap(
                        decision -> {
                            if (decision.getBehavior() == PermissionBehavior.PASSTHROUGH) {
                                return Mono.empty();
                            }
                            return Mono.just(decision);
                        });
    }

    private PermissionDecision checkExploreMode(ToolBase tool) {
        if (context.getMode() == PermissionMode.EXPLORE) {
            if (tool.isReadOnly()) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message(
                                "Permission granted for "
                                        + tool.getName()
                                        + " (explore mode - read-only tool)")
                        .decisionReason("Explore mode allows read-only operations")
                        .build();
            }
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(
                            "Permission denied for "
                                    + tool.getName()
                                    + " (explore mode is read-only)")
                    .decisionReason("Explore mode does not allow modifications")
                    .build();
        }
        if (context.getMode() == PermissionMode.ACCEPT_EDITS) {
            if (tool.isReadOnly()) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message(
                                "Permission granted for "
                                        + tool.getName()
                                        + " (accept edits mode - read-only tool)")
                        .decisionReason("Accept edits mode allows read-only operations")
                        .build();
            }
        }
        return null;
    }

    /**
     * ACCEPT_EDITS fallback for a tool that did not decide for itself: a write whose target
     * provably lives inside a configured working directory is auto-allowed (#2870).
     *
     * <p>This runs only after explicit rules and the tool's own {@code checkPermissions} — so
     * a tool's DENY, ALLOW or ASK always wins over the mode, and a path-shaped argument can
     * never turn a tool's refusal into an approval. The engine also cannot tell an edit tool
     * from any other tool taking a path, and cannot resolve a relative path the way the
     * executing tool will (its working root, or a remote/sandbox backend, is unknown here).
     * Both limits are handled by declining to decide: only an <em>absolute</em> target whose
     * <em>real</em> location — symlinks resolved on the nearest existing ancestor, so a link
     * cannot lead outside, and a not-yet-created file is judged by its existing parent — is
     * inside a working directory is auto-allowed. Anything else falls through to the default.
     *
     * @return the ALLOW decision, or {@code null} when the engine should keep going
     */
    private PermissionDecision acceptEditsInWorkingDirectory(
            ToolBase tool, Map<String, Object> input) {
        if (context.getMode() != PermissionMode.ACCEPT_EDITS) {
            return null;
        }
        String target = extractTargetPath(input);
        if (target == null || !Paths.get(target).isAbsolute()) {
            return null;
        }
        for (AdditionalWorkingDirectory wd : context.getWorkingDirectories().values()) {
            if (wd == null || wd.path() == null || wd.path().isBlank()) {
                continue;
            }
            if (isContainedByWorkingDirectory(wd.path(), target)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message(
                                "Permission granted for "
                                        + tool.getName()
                                        + " (accept edits mode - target inside working"
                                        + " directory)")
                        .decisionReason(
                                "Accept edits mode auto-allows edits inside working directory: "
                                        + wd.path())
                        .build();
            }
        }
        return null;
    }

    /**
     * Whether {@code target} is really inside {@code directory}, following symbolic links.
     *
     * <p>Both sides are resolved to their real path through the nearest existing ancestor, so
     * a lexical prefix alone is not enough: {@code /allowed/link/new.txt} with {@code link}
     * pointing outside the directory is rejected, while a link that stays inside is accepted.
     * When either real location cannot be established (unreadable path, IO error) this fails
     * closed and returns {@code false}.
     */
    private static boolean isContainedByWorkingDirectory(String directory, String target) {
        try {
            Path base = realPathOfExistingAncestor(Paths.get(directory));
            Path candidate = realPathOfExistingAncestor(Paths.get(target));
            return base != null && candidate != null && candidate.startsWith(base);
        } catch (IOException | RuntimeException unresolvable) {
            return false;
        }
    }

    /**
     * Returns the real (symlink-resolved) location of {@code path}, or of its nearest
     * existing ancestor when {@code path} itself does not exist yet, joined with the remaining
     * non-existing suffix. {@code null} when no ancestor exists.
     */
    private static Path realPathOfExistingAncestor(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        Deque<Path> missing = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        Path real = existing.toRealPath();
        for (Path segment : missing) {
            real = real.resolve(segment);
        }
        return real.normalize();
    }

    /**
     * Extracts the file target a tool operates on, from the parameter names the file tools
     * use ({@code path} / {@code file_path} / {@code filePath}); other shapes return {@code
     * null}. This only identifies <em>where</em> the tool writes, never <em>what kind</em> of
     * tool it is — the tool's own decision is consulted first for that.
     */
    private static String extractTargetPath(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        for (String key : new String[] {"path", "file_path", "filePath"}) {
            Object value = input.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private PermissionDecision checkDenyRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(denyRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.DENY)
                        .message("Permission to use " + tool.getName() + " has been denied")
                        .decisionReason("Rule: " + rule.ruleContent())
                        .build();
            }
        }
        return null;
    }

    private PermissionDecision checkAskRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(askRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ASK)
                        .message("Permission required for " + tool.getName())
                        .decisionReason("Rule: " + rule.ruleContent())
                        .build();
            }
        }
        return null;
    }

    private PermissionDecision checkAllowRules(ToolBase tool, Map<String, Object> input) {
        for (PermissionRule rule : rulesFor(allowRules, tool.getName())) {
            if (ruleMatches(tool, rule, input)) {
                return PermissionDecision.builder()
                        .behavior(PermissionBehavior.ALLOW)
                        .message("Permission granted for " + tool.getName())
                        .updatedInput(input)
                        .build();
            }
        }
        return null;
    }

    private static List<PermissionRule> rulesFor(
            Map<String, List<PermissionRule>> table, String toolName) {
        List<PermissionRule> rules = table.get(toolName);
        return rules == null ? List.of() : rules;
    }

    private boolean ruleMatches(ToolBase tool, PermissionRule rule, Map<String, Object> input) {
        String content = rule.ruleContent();
        if (content == null || content.isEmpty()) {
            return true;
        }
        return tool.matchRule(content, input);
    }

    private PermissionDecision defaultDecisionAsk(String toolName) {
        if (context.getMode() == PermissionMode.DONT_ASK) {
            return PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(
                            "Permission denied for "
                                    + toolName
                                    + " (dont_ask mode - user not available)")
                    .decisionReason("User is not available to answer permission prompts")
                    .build();
        }
        return PermissionDecision.builder()
                .behavior(PermissionBehavior.ASK)
                .message("Permission required for " + toolName)
                .decisionReason("Mode: " + context.getMode().name().toLowerCase(Locale.ROOT))
                .build();
    }
}
