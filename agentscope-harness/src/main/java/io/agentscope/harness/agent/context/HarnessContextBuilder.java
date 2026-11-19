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
package io.agentscope.harness.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.middleware.TaskContextProjection;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Single final request compiler. Instance is stateless and safe to share between sessions. */
public final class HarnessContextBuilder implements ModelRequestPreparer {
    /** Source id of the context item that carries the rendered skill catalog. */
    public static final String SKILL_CATALOG_SOURCE_ID = "harness:skills";

    private static final String ORIGINAL_SYSTEM_INPUTS =
            "agentscope.context.original_system_inputs";
    private static final String SKILL_CATALOG = "skill_catalog";
    private static final List<String> MATERIAL_KINDS =
            List.of(
                    "harness_instructions",
                    "runtime_materials",
                    "workspace_materials",
                    SKILL_CATALOG);

    private static final Logger log = LoggerFactory.getLogger(HarnessContextBuilder.class);
    private final ContextPolicy policy;
    private final CompactionMiddleware compaction;
    private final ToolResultEvictionMiddleware eviction;
    private final ContextSources sources;
    private final ContextSelectionPolicy selectionPolicy;

    public HarnessContextBuilder(
            ContextPolicy policy,
            CompactionMiddleware compaction,
            ToolResultEvictionMiddleware eviction) {
        this(
                policy,
                compaction,
                eviction,
                ContextSources.empty(),
                ContextSelectionPolicy.defaults());
    }

    public HarnessContextBuilder(
            ContextPolicy policy,
            CompactionMiddleware compaction,
            ToolResultEvictionMiddleware eviction,
            ContextSources sources,
            ContextSelectionPolicy selectionPolicy) {
        this.policy = Objects.requireNonNull(policy);
        this.compaction = compaction;
        this.eviction = eviction;
        this.sources = Objects.requireNonNull(sources);
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy);
    }

    public ContextSources sources() {
        return sources;
    }

    public ContextSelectionPolicy selectionPolicy() {
        return selectionPolicy;
    }

    private record ProjectionSnapshot(
            TaskContextState tasks, boolean planActive, String planFile) {}

    public ContextPolicy policy() {
        return policy;
    }

    @Override
    public boolean handlesTaskProjection() {
        return true;
    }

    @Override
    public Mono<ModelCallInput> prepare(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            String callId,
            Purpose purpose) {
        return Mono.defer(
                        () -> {
                            RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
                            String key = ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + callId;
                            rc.put(key, null);
                            return Mono.defer(() -> compile(agent, rc, input, callId, purpose))
                                    .doOnSuccess(ignored -> observe((ContextManifest) rc.get(key)))
                                    .doOnError(
                                            error -> {
                                                ContextManifest previous =
                                                        (ContextManifest) rc.get(key);
                                                ContextManifest failed =
                                                        previous == null
                                                                ? new ContextManifest(
                                                                        callId,
                                                                        purpose.name(),
                                                                        input.model()
                                                                                .getModelName(),
                                                                        "harness-context-v2",
                                                                        -1,
                                                                        -1,
                                                                        "unavailable",
                                                                        false,
                                                                        0,
                                                                        List.of(),
                                                                        List.of(),
                                                                        "build_failed")
                                                                : previous.withValidation(
                                                                        previous.validation()
                                                                                        .equals(
                                                                                                "passed")
                                                                                ? "build_failed"
                                                                                : previous
                                                                                        .validation());
                                                rc.put(key, failed);
                                                observe(failed);
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ModelCallInput> compile(
            Agent agent, RuntimeContext rc, ModelCallInput input, String callId, Purpose purpose) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        List<Msg> original = state == null ? List.of() : List.copyOf(state.contextMutable());
        ProjectionSnapshot snapshot =
                state == null
                        ? null
                        : new ProjectionSnapshot(
                                state.getTasksContext().snapshot(),
                                state.getPlanModeContext().isPlanActive(),
                                state.getPlanModeContext().getCurrentPlanFile());
        WorkspaceContextMaterials materials = rc.get(WorkspaceContextMaterials.class);
        List<ContextItem> workspace =
                materials == null ? List.of() : List.copyOf(materials.items());
        var readContext =
                new ContextRequest(
                        agent == null ? null : agent.getAgentId(),
                        rc.getUserId(),
                        rc.getSessionId(),
                        callId,
                        purpose);
        return sources.collect(readContext)
                .flatMap(
                        collected -> {
                            var selected = new ArrayList<>(workspace);
                            selected.addAll(collected.items());
                            Set<String> sourceIds = new HashSet<>();
                            for (ContextItem item : selected) {
                                if (!sourceIds.add(item.sourceId()))
                                    throw new IllegalArgumentException(
                                            "Duplicate context source: " + item.sourceId());
                            }
                            return compileSnapshot(
                                    agent,
                                    rc,
                                    input,
                                    callId,
                                    purpose,
                                    state,
                                    snapshot,
                                    original,
                                    selected,
                                    new ArrayList<>(collected.transforms()));
                        });
    }

    private Mono<ModelCallInput> compileSnapshot(
            Agent agent,
            RuntimeContext rc,
            ModelCallInput input,
            String callId,
            Purpose purpose,
            AgentState state,
            ProjectionSnapshot snapshot,
            List<Msg> original,
            List<ContextItem> selected,
            List<String> transforms) {
        List<Msg> history = original;
        if (purpose != Purpose.REASONING) {
            selected.removeIf(item -> item.placement() != ContextItem.Placement.SYSTEM);
        }
        ModelCallInput current = withMaterials(input, selected, history);
        if (purpose == Purpose.REASONING && eviction != null) {
            var candidate =
                    eviction.prepareCandidate(
                            agent,
                            rc,
                            new ReasoningInput(
                                    current.messages(), current.tools(), current.options()),
                            history);
            if (!candidate.input().messages().equals(current.messages()))
                transforms.add("tool_result_offload");
            current = copy(current, candidate.input().messages());
            history = candidate.history();
        }
        long limit = inputLimit(current);
        if (purpose == Purpose.REASONING) {
            List<String> order = List.copyOf(selectionPolicy.omissionOrder(List.copyOf(selected)));
            Map<String, ContextItem> byId = new LinkedHashMap<>();
            selected.forEach(item -> byId.put(item.sourceId(), item));
            Set<String> seen = new HashSet<>();
            for (String id : order) {
                ContextItem item = byId.get(id);
                if (item == null
                        || item.required()
                        || item.placement() == ContextItem.Placement.SYSTEM
                        || !seen.add(id))
                    throw new IllegalArgumentException("Invalid context omission candidate: " + id);
            }
            for (String id : order) {
                if (policy.estimator().estimate(project(current, snapshot)).tokens() <= limit)
                    break;
                selected.remove(byId.get(id));
                transforms.add("omitted_for_budget:" + id);
                current = withMaterials(current, selected, history);
            }
        }
        List<Msg> canonical = history;
        List<Msg> fixed =
                (purpose == Purpose.REASONING ? project(current, snapshot) : current)
                        .messages().stream()
                                .filter(
                                        msg ->
                                                msg.getRole() == MsgRole.SYSTEM
                                                        || msg.isRequestProjection(canonical))
                                .toList();
        long fixedTokens = policy.estimator().estimate(copy(current, fixed)).tokens();
        int conversationBudget =
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, limit - fixedTokens));
        ModelCallInput before = current;
        List<Msg> prefix =
                current.messages().stream()
                        .filter(
                                msg ->
                                        msg.getRole() == MsgRole.SYSTEM
                                                || msg.isRequestProjection(canonical))
                        .toList();
        Mono<CompactionMiddleware.Candidate> compiled;
        if (purpose == Purpose.REASONING && compaction != null) {
            long requestOverhead = policy.estimator().estimate(copy(current, List.of())).tokens();
            compiled =
                    compaction.prepareCandidate(
                            agent,
                            rc,
                            current,
                            conversationBudget,
                            history,
                            messages -> {
                                List<Msg> assembled = new ArrayList<>(prefix);
                                assembled.addAll(messages);
                                ModelCallInput request =
                                        layout(
                                                project(copy(before, assembled), snapshot),
                                                canonical);
                                // Measure the complete candidate, charging fixed inputs once.
                                return Math.max(
                                        0,
                                        policy.estimator().estimate(request).tokens()
                                                - fixedTokens);
                            },
                            message ->
                                    Math.max(
                                            0,
                                            policy.estimator()
                                                            .estimate(
                                                                    copy(before, List.of(message)))
                                                            .tokens()
                                                    - requestOverhead));
        } else {
            compiled = Mono.just(new CompactionMiddleware.Candidate(current, history));
        }
        return compiled.map(
                candidate -> {
                    ModelCallInput result = candidate.input();
                    if (!result.messages().equals(before.messages()))
                        transforms.add("history_compaction");
                    ModelCallInput prepared =
                            layout(
                                    purpose == Purpose.REASONING
                                            ? project(result, snapshot)
                                            : result,
                                    candidate.history());
                    var estimate = policy.estimator().estimate(prepared);
                    long finalLimit = inputLimit(prepared);
                    RuntimeException validationError = null;
                    String validation = "passed";
                    try {
                        validateToolPairs(prepared.messages());
                    } catch (IllegalArgumentException error) {
                        validationError = error;
                        validation = "invalid_tool_pairs";
                    }
                    if (validationError == null && estimate.tokens() > finalLimit) {
                        validationError =
                                new ContextBudgetExceededException(estimate.tokens(), finalLimit);
                        validation = "budget_exceeded";
                    }
                    List<ContextManifest.Item> items = new ArrayList<>();
                    for (Msg msg : prepared.messages()) {
                        String kind =
                                msg.getRole() == MsgRole.SYSTEM
                                        ? "system_input"
                                        : msg.isRequestProjection(candidate.history())
                                                ? "runtime_projection"
                                                : "message";
                        items.add(
                                new ContextManifest.Item(
                                        kind,
                                        msg.getId(),
                                        hash(JsonUtils.getJsonCodec().toJson(msg.getContent()))));
                    }
                    for (var tool : prepared.tools()) {
                        items.add(
                                new ContextManifest.Item(
                                        "tool_schema",
                                        tool.getName(),
                                        hash(JsonUtils.getJsonCodec().toJson(tool))));
                    }
                    for (ContextItem item : selected) {
                        if (!item.content().isBlank())
                            items.add(
                                    new ContextManifest.Item(
                                            item.kind(),
                                            item.sourceId(),
                                            hash(item.content()),
                                            item.revision(),
                                            item.placement().name(),
                                            item.required(),
                                            item.priority()));
                    }
                    ContextManifest manifest =
                            new ContextManifest(
                                    callId,
                                    purpose.name(),
                                    prepared.model().getModelName(),
                                    "harness-context-v2",
                                    estimate.tokens(),
                                    finalLimit,
                                    estimate.method(),
                                    estimate.exact(),
                                    snapshot == null ? 0 : snapshot.tasks().getRevision(),
                                    items,
                                    transforms,
                                    validation);
                    rc.put(ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + callId, manifest);
                    if (validationError != null) throw validationError;
                    // Agent calls serialize access to state. Recheck the snapshot after
                    // asynchronous work;
                    // never overwrite a history changed by a hook or another owner while preparing.
                    if (state != null) {
                        if (state.getTasksContext().getRevision() != snapshot.tasks().getRevision()
                                || state.getPlanModeContext().isPlanActive()
                                        != snapshot.planActive()
                                || !Objects.equals(
                                        state.getPlanModeContext().getCurrentPlanFile(),
                                        snapshot.planFile())) {
                            rc.put(
                                    ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + callId,
                                    manifest.withValidation("state_conflict"));
                            throw new ConcurrentModificationException(
                                    "Task or plan changed during context preparation");
                        }
                        if (!state.contextMutable().equals(original)) {
                            rc.put(
                                    ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + callId,
                                    manifest.withValidation("history_conflict"));
                            throw new ConcurrentModificationException(
                                    "History changed during context preparation");
                        }
                        if (!candidate.history().equals(original)) {
                            state.contextMutable().clear();
                            state.contextMutable().addAll(candidate.history());
                        }
                    }
                    return prepared;
                });
    }

    private void observe(ContextManifest manifest) {
        try {
            policy.observer().accept(manifest);
        } catch (RuntimeException error) {
            // Telemetry is not authority to reject an otherwise valid request.
            log.warn("Context observer failed: {}", error.getClass().getSimpleName());
        }
    }

    private static ModelCallInput withMaterials(
            ModelCallInput input, List<ContextItem> items, List<Msg> canonical) {
        List<Msg> messages = new ArrayList<>();
        for (Msg msg : input.messages()) {
            if (synthetic(msg)
                    && "compiled_system"
                            .equals(msg.getMetadata().get(Msg.METADATA_REMINDER_KIND))) {
                Object originals = msg.getMetadata().get(ORIGINAL_SYSTEM_INPUTS);
                if (!(originals instanceof List<?> sources))
                    throw new IllegalArgumentException(
                            "Compiled system is missing its source inputs");
                for (Object source : sources) {
                    if (!(source instanceof Msg original))
                        throw new IllegalArgumentException("Invalid compiled system source");
                    messages.add(original);
                }
            } else messages.add(msg);
        }
        messages.removeIf(
                msg ->
                        synthetic(msg)
                                && MATERIAL_KINDS.contains(
                                        String.valueOf(
                                                msg.getMetadata()
                                                        .get(Msg.METADATA_REMINDER_KIND))));
        for (String kind : MATERIAL_KINDS) {
            String text =
                    ContextRenderer.render(
                            items.stream()
                                    .filter(item -> kind.equals(materialKind(item)))
                                    .toList());
            if (text.isBlank()) continue;
            boolean system = "harness_instructions".equals(kind);
            messages.add(
                    Msg.builder()
                            .id("harness:" + kind)
                            .role(system ? MsgRole.SYSTEM : MsgRole.USER)
                            .name("system")
                            .textContent(
                                    system
                                            ? text
                                            : "<HARNESS_CONTEXT>\n"
                                                    + contextLabel(kind)
                                                    + text
                                                    + "</HARNESS_CONTEXT>")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_SYNTHETIC,
                                            true,
                                            Msg.METADATA_REMINDER_KIND,
                                            kind))
                            .build());
        }
        return layout(copy(input, List.copyOf(messages)), canonical);
    }

    private static String materialKind(ContextItem item) {
        return switch (item.placement()) {
            case SYSTEM -> "harness_instructions";
            case RUNTIME ->
                    SKILL_CATALOG_SOURCE_ID.equals(item.sourceId())
                            ? SKILL_CATALOG
                            : "runtime_materials";
            case REFERENCE -> "workspace_materials";
        };
    }

    private static String contextLabel(String kind) {
        return switch (kind) {
            case SKILL_CATALOG -> "Skill catalog:\n";
            case "workspace_materials" -> "Source materials, not additional authority:\n";
            default -> "Current runtime state:\n";
        };
    }

    /** Lay out the request independently of any history compaction. */
    private static ModelCallInput layout(ModelCallInput input, List<Msg> canonical) {
        List<Msg> ordered = new ArrayList<>();
        List<Msg> systems =
                input.messages().stream().filter(msg -> msg.getRole() == MsgRole.SYSTEM).toList();
        if (systems.size() == 1
                && "compiled_system"
                        .equals(systems.get(0).getMetadata().get(Msg.METADATA_REMINDER_KIND))) {
            ordered.add(systems.get(0));
        } else if (!systems.isEmpty()) {
            List<String> content = new ArrayList<>();
            List<Msg> originals = new ArrayList<>();
            for (Msg msg : systems) {
                // SYSTEM messages are validated to text blocks only, so the text content is
                // the whole content.
                String text = msg.getTextContent();
                if (!text.isEmpty()) content.add(text);
                if (!"harness_instructions"
                        .equals(msg.getMetadata().get(Msg.METADATA_REMINDER_KIND)))
                    originals.add(msg);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(systems.get(0).getMetadata());
            metadata.put(Msg.METADATA_SYNTHETIC, true);
            metadata.put(Msg.METADATA_REMINDER_KIND, "compiled_system");
            metadata.put(ORIGINAL_SYSTEM_INPUTS, List.copyOf(originals));
            ordered.add(
                    Msg.builder()
                            .id("harness:system")
                            .role(MsgRole.SYSTEM)
                            .name("system")
                            .textContent(String.join("\n\n", content))
                            .metadata(metadata)
                            .build());
        }
        int afterSystem = ordered.size();
        Msg catalog = null;
        for (int group = 1; group < 4; group++) {
            for (Msg msg : input.messages()) {
                int position =
                        msg.getRole() == MsgRole.SYSTEM
                                ? 0
                                : !msg.isRequestProjection(canonical)
                                        ? 1
                                        : "workspace_materials"
                                                        .equals(
                                                                msg.getMetadata()
                                                                        .get(
                                                                                Msg
                                                                                        .METADATA_REMINDER_KIND))
                                                ? 3
                                                : 2;
                if (position != group) continue;
                if (group == 2
                        && SKILL_CATALOG.equals(
                                msg.getMetadata().get(Msg.METADATA_REMINDER_KIND))) {
                    catalog = msg;
                } else {
                    ordered.add(msg);
                }
            }
        }
        if (catalog != null) {
            // A trailing assistant message can be interpreted as a prefill.
            int end = ordered.size();
            ordered.add(
                    end > 0 && ordered.get(end - 1).getRole() == MsgRole.ASSISTANT
                            ? end
                            : afterSystem,
                    catalog);
        }
        return copy(input, List.copyOf(ordered));
    }

    private ModelCallInput project(ModelCallInput input, ProjectionSnapshot state) {
        if (state == null) return input;
        List<Msg> messages =
                TaskContextProjection.project(
                        input.messages(),
                        state.tasks(),
                        sources.taskContext().requirements(),
                        sources.taskContext().verificationResults());
        List<Msg> rebuilt = new ArrayList<>();
        for (Msg msg : messages) {
            if (!(synthetic(msg)
                    && "plan_state".equals(msg.getMetadata().get(Msg.METADATA_REMINDER_KIND)))) {
                rebuilt.add(msg);
            }
        }
        if (state.planActive() || state.planFile() != null) {
            String text =
                    "<RUNTIME_STATE>\nMode: "
                            + (state.planActive() ? "PLAN (read-only)" : "BUILD")
                            + "\nPlan reference: "
                            + escape(state.planFile())
                            + "\n"
                            + "This is a mode/path projection, not evidence of approval or"
                            + " completed work.\n"
                            + "</RUNTIME_STATE>";
            rebuilt.add(
                    Msg.builder()
                            .id("harness:plan_state")
                            .role(MsgRole.USER)
                            .name("system")
                            .textContent(text)
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_SYNTHETIC,
                                            true,
                                            Msg.METADATA_REMINDER_KIND,
                                            "plan_state"))
                            .build());
        }
        return copy(input, List.copyOf(rebuilt));
    }

    private long inputLimit(ModelCallInput input) {
        long window = input.model().getContextWindowSize();
        long output = policy.reservedOutputTokens();
        if (output == 0) output = window > 0 ? Math.min(4096, Math.max(1, window / 8)) : 4096;
        if (input.options() != null) {
            if (input.options().getMaxTokens() != null)
                output = Math.max(output, input.options().getMaxTokens());
            if (input.options().getMaxCompletionTokens() != null)
                output = Math.max(output, input.options().getMaxCompletionTokens());
        }
        long safety =
                policy.safetyMarginTokens() > 0
                        ? policy.safetyMarginTokens()
                        : window > 0 ? Math.max(1, window / 50) : 1024;
        long limit = window > 0 ? Math.max(0, window - output - safety) : 64000;
        return policy.maxInputTokens() > 0 ? Math.min(limit, policy.maxInputTokens()) : limit;
    }

    private static ModelCallInput copy(ModelCallInput input, List<Msg> messages) {
        return new ModelCallInput(
                messages, List.copyOf(input.tools()), input.options(), input.model());
    }

    private static boolean synthetic(Msg msg) {
        return msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
    }

    private static String escape(String text) {
        return text == null
                ? "(none)"
                : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Preserve call/result identities; never manufacture results to repair an invalid history. */
    static void validateToolPairs(List<Msg> messages) {
        Set<String> pending = new HashSet<>();
        for (Msg msg : messages) {
            for (var block : msg.getContent()) {
                if (block instanceof ToolUseBlock use) {
                    if (use.getId() == null || !pending.add(use.getId())) {
                        throw new IllegalArgumentException("Duplicate or missing tool call id");
                    }
                } else if (block instanceof ToolResultBlock result) {
                    if (!pending.remove(result.getId())) {
                        throw new IllegalArgumentException(
                                "Tool result without matching call: " + result.getId());
                    }
                }
            }
        }
        if (!pending.isEmpty())
            throw new IllegalArgumentException("Unresolved tool calls in model input");
    }
}
