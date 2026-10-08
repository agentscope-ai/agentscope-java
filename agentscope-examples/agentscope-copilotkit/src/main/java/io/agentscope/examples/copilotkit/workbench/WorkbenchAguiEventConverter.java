/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.copilotkit.workbench;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Maps the workbench's {@link CustomEvent}s onto the AG-UI frames a CopilotKit client understands.
 *
 * <p>This is the one place where the example decides which AG-UI event best expresses each piece of
 * agent progress:
 *
 * <ul>
 *   <li>{@code state_updated} → {@code STATE_SNAPSHOT} for the first frame of a run, then
 *       {@code STATE_DELTA} (RFC 6902) for every later change, so the browser only pays for what
 *       actually moved
 *   <li>{@code plan_step_started} / {@code plan_step_finished} → {@code STEP_STARTED} /
 *       {@code STEP_FINISHED}, giving plan tasks a first-class lifecycle on the wire
 *   <li>{@code a2ui_surface} → {@code ACTIVITY_SNAPSHOT} of type {@code a2ui-surface}, which is how
 *       generated UI reaches the A2UI renderer
 *   <li>{@code permission_audit} → {@code CUSTOM}, consumed by the audit panel
 * </ul>
 *
 * <p>Snapshot bookkeeping is keyed by {@code threadId:runId}. Entries are dropped as soon as a run
 * finishes, so a long-lived server does not accumulate them.
 *
 */
@Component
public class WorkbenchAguiEventConverter implements AgentEventConverter {

    /** AG-UI activity type paired with {@code useRenderActivity("a2ui-surface")} in the browser. */
    public static final String ACTIVITY_A2UI_SURFACE = "a2ui-surface";

    private final Map<String, Map<String, Object>> lastSnapshots = new ConcurrentHashMap<>();

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(CustomEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        CustomEvent custom = (CustomEvent) event;
        String name = custom.getName();
        Object value = custom.getValue();

        switch (name == null ? "" : name) {
            case WorkbenchState.EVENT_STATE_UPDATED -> emitState(context, asMap(value));
            case WorkbenchState.EVENT_STEP_STARTED ->
                    context.emit(
                            new AguiEvent.StepStarted(
                                    context.getThreadId(), context.getRunId(), stepName(value)));
            case WorkbenchState.EVENT_STEP_FINISHED ->
                    context.emit(
                            new AguiEvent.StepFinished(
                                    context.getThreadId(), context.getRunId(), stepName(value)));
            case WorkbenchState.EVENT_A2UI_SURFACE -> emitSurface(context, asMap(value));
            default ->
                    context.emit(
                            new AguiEvent.Custom(
                                    context.getThreadId(), context.getRunId(), name, value));
        }
    }

    /** Drops the per-run snapshot baseline; called when the run ends. */
    public void forgetRun(String threadId, String runId) {
        lastSnapshots.remove(runKey(threadId, runId));
    }

    private void emitState(AguiStreamContext context, Map<String, Object> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        String key = runKey(context.getThreadId(), context.getRunId());
        Map<String, Object> previous = lastSnapshots.put(key, snapshot);

        if (previous == null) {
            context.emit(
                    new AguiEvent.StateSnapshot(
                            context.getThreadId(), context.getRunId(), snapshot));
            return;
        }

        List<AguiEvent.JsonPatchOperation> patch = diff(previous, snapshot);
        if (patch.isEmpty()) {
            return;
        }
        context.emit(new AguiEvent.StateDelta(context.getThreadId(), context.getRunId(), patch));
    }

    private void emitSurface(AguiStreamContext context, Map<String, Object> payload) {
        Object surfaceId = payload.get("surfaceId");
        if (surfaceId == null) {
            return;
        }
        context.emit(
                new AguiEvent.ActivitySnapshot(
                        context.getThreadId(),
                        context.getRunId(),
                        "a2ui-" + surfaceId,
                        ACTIVITY_A2UI_SURFACE,
                        payload));
    }

    /**
     * Builds a top-level RFC 6902 patch between two snapshots.
     *
     * <p>Nested objects are replaced wholesale rather than diffed recursively — the workbench state
     * is small, and a shallow patch keeps the emitted frames easy to read in the event inspector.
     */
    private static List<AguiEvent.JsonPatchOperation> diff(
            Map<String, Object> previous, Map<String, Object> current) {
        List<AguiEvent.JsonPatchOperation> patch = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(previous.keySet());
        keys.addAll(current.keySet());

        for (String key : keys) {
            boolean existed = previous.containsKey(key);
            boolean exists = current.containsKey(key);
            String path = "/" + key;
            if (existed && !exists) {
                patch.add(new AguiEvent.JsonPatchOperation("remove", path, null, null));
            } else if (!existed) {
                patch.add(new AguiEvent.JsonPatchOperation("add", path, current.get(key), null));
            } else if (!Objects.equals(previous.get(key), current.get(key))) {
                patch.add(
                        new AguiEvent.JsonPatchOperation("replace", path, current.get(key), null));
            }
        }
        return patch;
    }

    private static String stepName(Object value) {
        Map<String, Object> payload = asMap(value);
        Object step = payload.get("step");
        return step == null ? "plan-step" : String.valueOf(step);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
    }

    private static String runKey(String threadId, String runId) {
        return threadId + ":" + runId;
    }
}
