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

import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.state.Task;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-scoped shared state backing the CopilotKit workbench.
 *
 * <p>Every mutation queues a {@link CustomEvent}. {@code WorkbenchEventMiddleware} drains the queue
 * while the agent is still streaming and {@code WorkbenchAguiEventConverter} turns each queued event
 * into the matching AG-UI frame ({@code STATE_SNAPSHOT} / {@code STATE_DELTA} /
 * {@code STEP_STARTED} / {@code STEP_FINISHED} / {@code ACTIVITY_SNAPSHOT} / {@code CUSTOM}). That
 * round trip is what makes plan progress and permission decisions appear in the browser mid-run
 * instead of after it.
 *
 * <p>All mutators are synchronized because AgentScope may execute tool calls in parallel.
 *
 */
public final class WorkbenchState {

    /** Carries a full workbench snapshot; converted to STATE_SNAPSHOT or STATE_DELTA. */
    public static final String EVENT_STATE_UPDATED = "state_updated";

    /** A plan task entered {@code in_progress}; converted to STEP_STARTED. */
    public static final String EVENT_STEP_STARTED = "plan_step_started";

    /** A plan task reached {@code completed}; converted to STEP_FINISHED. */
    public static final String EVENT_STEP_FINISHED = "plan_step_finished";

    /** Carries A2UI operations; converted to an {@code a2ui-surface} ACTIVITY_SNAPSHOT. */
    public static final String EVENT_A2UI_SURFACE = "a2ui_surface";

    /** Carries a permission decision; converted to a CUSTOM event for the audit panel. */
    public static final String EVENT_PERMISSION_AUDIT = "permission_audit";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_AUDIT_ENTRIES = 30;

    private final String threadId;
    private final Deque<CustomEvent> pending = new ConcurrentLinkedDeque<>();

    private String topic = "AgentScope 2.0 × CopilotKit 能力演示";
    private String priority = "中";
    private String status = "空闲";
    private boolean approved;
    private String updatedAt = LocalTime.now().format(TIME);

    private String planGoal;
    private String planPhase = "idle";
    private final List<Map<String, Object>> tasks = new ArrayList<>();

    /**
     * Steps that have emitted {@code STEP_STARTED} but not yet {@code STEP_FINISHED}.
     *
     * <p>Keyed by task id → the exact {@code stepName} used on the wire. AG-UI rejects
     * {@code RUN_FINISHED} while any step is still active, and {@code STEP_FINISHED} must reuse the
     * same name as its matching start.
     */
    private final Map<String, String> openSteps = new LinkedHashMap<>();

    private final List<Map<String, Object>> permissionAudit = new ArrayList<>();

    private Map<String, Object> metrics = Map.of();

    private volatile Set<String> clientComponents = Set.of();

    private String a2uiSurfaceId;
    private String a2uiCatalogId;
    private String a2uiGeneratedBy;
    private String a2uiIntent;
    private int a2uiComponentCount;

    WorkbenchState(String threadId) {
        this.threadId = threadId;
    }

    public String getThreadId() {
        return threadId;
    }

    /**
     * The custom A2UI components the browser declared it can paint.
     *
     * <p>Reported by the frontend through {@code useAgentContext} and picked up by
     * {@code WorkbenchEventMiddleware}, so the composer only ever advertises components this client
     * has a renderer for. An empty set means the browser stayed silent and the full server-side
     * catalog is used.
     */
    public Set<String> getClientComponents() {
        return clientComponents;
    }

    public void setClientComponents(Set<String> names) {
        if (names != null && !names.isEmpty()) {
            this.clientComponents = Set.copyOf(names);
        }
    }

    /** Immutable view of the state, shaped exactly like the browser's shared-state object. */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("goal", planGoal);
        plan.put("phase", planPhase);
        plan.put("tasks", List.copyOf(tasks));
        plan.put("total", tasks.size());
        plan.put("completed", completedCount());
        plan.put("progress", progress());

        Map<String, Object> a2ui = new LinkedHashMap<>();
        a2ui.put("surfaceId", a2uiSurfaceId);
        a2ui.put("catalogId", a2uiCatalogId);
        a2ui.put("generatedBy", a2uiGeneratedBy);
        a2ui.put("intent", a2uiIntent);
        a2ui.put("componentCount", a2uiComponentCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("topic", topic);
        snapshot.put("priority", priority);
        snapshot.put("status", status);
        snapshot.put("approved", approved);
        snapshot.put("updatedAt", updatedAt);
        snapshot.put("plan", plan);
        snapshot.put("permissionAudit", List.copyOf(permissionAudit));
        snapshot.put("metrics", metrics);
        snapshot.put("a2ui", a2ui);
        return snapshot;
    }

    private long completedCount() {
        return tasks.stream().filter(task -> "completed".equals(task.get("state"))).count();
    }

    private int progress() {
        if (tasks.isEmpty()) {
            return 0;
        }
        return (int) Math.round(completedCount() * 100.0 / tasks.size());
    }

    /** Seeds the state from the {@code state} object the browser sent along with the run. */
    public synchronized void mergeFromClient(Map<String, Object> clientState) {
        if (clientState == null || clientState.isEmpty()) {
            return;
        }
        if (clientState.get("topic") instanceof String value && !value.isBlank()) {
            topic = value;
        }
        if (clientState.get("priority") instanceof String value && !value.isBlank()) {
            priority = value;
        }
        if (clientState.get("status") instanceof String value && !value.isBlank()) {
            status = value;
        }
        if (clientState.get("approved") instanceof Boolean value) {
            approved = value;
        }
    }

    public synchronized String updateBrief(String newTopic, String newPriority, String newStatus) {
        if (newTopic != null && !newTopic.isBlank()) {
            topic = newTopic.trim();
        }
        if (newPriority != null && !newPriority.isBlank()) {
            priority = newPriority.trim();
        }
        if (newStatus != null && !newStatus.isBlank()) {
            status = newStatus.trim();
        }
        touch();
        return "已更新：主题=%s，优先级=%s，状态=%s".formatted(topic, priority, status);
    }

    public synchronized void setPlanGoal(String goal) {
        if (goal != null && !goal.isBlank()) {
            planGoal = goal.trim();
            touch();
        }
    }

    /**
     * Mirrors AgentScope's live task list into the shared state.
     *
     * <p>Transitions are diffed against the previous mirror so that entering {@code in_progress} or
     * {@code completed} also queues the matching AG-UI step event. Open steps are tracked so a later
     * {@link #closeOpenSteps()} can guarantee every {@code STEP_STARTED} has a {@code STEP_FINISHED}
     * before {@code RUN_FINISHED}.
     *
     * @param liveTasks the current {@code AgentState.getTasksContext().getTasks()}
     * @return true when anything changed and a state frame was queued
     */
    public synchronized boolean syncTasks(List<Task> liveTasks) {
        List<Map<String, Object>> mirrored = new ArrayList<>();
        for (Task task : liveTasks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", task.getId());
            entry.put("title", task.getSubject());
            entry.put("state", task.getState().getWire());
            Object taskPriority = task.getMetadata().get("priority");
            entry.put("priority", taskPriority == null ? "medium" : taskPriority);
            mirrored.add(entry);
        }
        if (mirrored.equals(tasks)) {
            return false;
        }

        Map<String, String> previousStates = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) {
            previousStates.put(String.valueOf(task.get("id")), String.valueOf(task.get("state")));
        }
        tasks.clear();
        tasks.addAll(mirrored);

        Set<String> liveIds = new LinkedHashSet<>();
        for (Map<String, Object> task : mirrored) {
            String id = String.valueOf(task.get("id"));
            liveIds.add(id);
            String state = String.valueOf(task.get("state"));
            if (state.equals(previousStates.get(id))) {
                continue;
            }
            String title = String.valueOf(task.get("title"));
            if ("in_progress".equals(state)) {
                openStep(id, title);
            } else if ("completed".equals(state)) {
                // AG-UI forbids STEP_FINISHED without a prior STEP_STARTED for the same name.
                if (!openSteps.containsKey(id)) {
                    openStep(id, title);
                }
                finishStep(id);
            } else if (openSteps.containsKey(id)) {
                // Left in_progress for another state (e.g. pending) — still must close the step.
                finishStep(id);
            }
        }
        // Tasks dropped from the plan while still open would otherwise block RUN_FINISHED.
        for (String id : List.copyOf(openSteps.keySet())) {
            if (!liveIds.contains(id)) {
                finishStep(id);
            }
        }

        long done = completedCount();
        if (tasks.isEmpty()) {
            planPhase = "idle";
        } else if (done == tasks.size()) {
            planPhase = "completed";
        } else if (done > 0 || tasks.stream().anyMatch(t -> "in_progress".equals(t.get("state")))) {
            planPhase = "executing";
        } else {
            planPhase = "planning";
        }
        status = "计划进度 %d/%d".formatted(done, tasks.size());
        touch();
        return true;
    }

    public synchronized void recordMetrics(Map<String, Object> newMetrics) {
        metrics = newMetrics == null ? Map.of() : Map.copyOf(newMetrics);
        touch();
    }

    /** Publishes a freshly generated A2UI surface to the browser. */
    public synchronized void recordA2uiSurface(
            String surfaceId,
            String catalogId,
            String intent,
            String generatedBy,
            int componentCount,
            List<Map<String, Object>> operations) {
        this.a2uiSurfaceId = surfaceId;
        this.a2uiCatalogId = catalogId;
        this.a2uiIntent = intent;
        this.a2uiGeneratedBy = generatedBy;
        this.a2uiComponentCount = componentCount;
        status = "已生成 A2UI 界面（%s）".formatted(generatedBy);
        touch();

        Map<String, Object> payload = new LinkedHashMap<>();
        // CopilotKit's A2UI message renderer only paints when it finds this exact key
        // (A2UI_OPERATIONS_KEY in @copilotkit/react-core); everything else is metadata.
        payload.put("a2ui_operations", operations);
        payload.put("surfaceId", surfaceId);
        payload.put("catalogId", catalogId);
        payload.put("intent", intent);
        payload.put("generatedBy", generatedBy);
        pending.add(new CustomEvent(EVENT_A2UI_SURFACE, payload));
    }

    /** Queues a state frame even when nothing changed; used at the start of every run. */
    public synchronized void queueSnapshot() {
        pending.add(new CustomEvent(EVENT_STATE_UPDATED, snapshot()));
    }

    /**
     * Drops any leftover open-step bookkeeping from a previous run without emitting events.
     *
     * <p>Called on {@code AgentStartEvent}: a prior run that ended in {@code RUN_ERROR} is allowed
     * to leave steps open on the wire, but those names must not leak into the next run's pairing.
     */
    public synchronized void clearOpenSteps() {
        openSteps.clear();
    }

    /**
     * Emits {@code STEP_FINISHED} for every step still open.
     *
     * <p>Must run before {@code AgentEndEvent} is converted to {@code RUN_FINISHED}; the AG-UI
     * verifier rejects a finished run while any step is still active.
     */
    public synchronized void closeOpenSteps() {
        for (String id : List.copyOf(openSteps.keySet())) {
            finishStep(id);
        }
    }

    private void openStep(String taskId, String title) {
        if (openSteps.containsKey(taskId)) {
            return;
        }
        String step = resolveStepName(taskId, title);
        openSteps.put(taskId, step);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("step", step);
        pending.add(new CustomEvent(EVENT_STEP_STARTED, payload));
    }

    private void finishStep(String taskId) {
        String step = openSteps.remove(taskId);
        if (step == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("step", step);
        pending.add(new CustomEvent(EVENT_STEP_FINISHED, payload));
    }

    /**
     * Picks a wire {@code stepName} that is unique among currently open steps.
     *
     * <p>Titles are preferred for readability in the event inspector; colliding titles get the task
     * id appended so a second {@code STEP_STARTED} does not trip "already active".
     */
    private String resolveStepName(String taskId, String title) {
        String base =
                title == null || title.isBlank() || "null".equals(title) ? taskId : title.trim();
        for (Map.Entry<String, String> entry : openSteps.entrySet()) {
            if (!entry.getKey().equals(taskId) && base.equals(entry.getValue())) {
                return base + " [" + taskId + "]";
            }
        }
        return base;
    }

    private void touch() {
        updatedAt = LocalTime.now().format(TIME);
        pending.add(new CustomEvent(EVENT_STATE_UPDATED, snapshot()));
    }

    /** Removes and returns everything queued since the previous drain. */
    public List<CustomEvent> drainPendingEvents() {
        List<CustomEvent> drained = new ArrayList<>();
        CustomEvent event;
        while ((event = pending.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }
}
