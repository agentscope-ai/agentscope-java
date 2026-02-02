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
package io.agentscope.core.plan;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.plan.hint.DefaultPlanToHint;
import io.agentscope.core.plan.hint.PlanToHint;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import io.agentscope.core.plan.storage.InMemoryPlanStorage;
import io.agentscope.core.plan.storage.PlanStorage;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.PlanNotebookState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Plan notebook for managing complex tasks through structured planning.
 *
 * <p>Provides tool functions for agents to create, manage, and track plans. Automatically injects
 * contextual hints to guide agent execution through a hook-based mechanism.
 *
 * <p><b>Core Features:</b>
 *
 * <ul>
 *   <li><b>Plan Management:</b> Create, revise, and finish plans with multiple subtasks
 *   <li><b>Automatic Hint Injection:</b> Injects contextual hints before each reasoning step
 *   <li><b>State Tracking:</b> Tracks subtask states (todo/in_progress/done/abandoned)
 *   <li><b>Historical Plans:</b> Stores and recovers historical plans
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Create PlanNotebook with custom configuration
 * PlanNotebook planNotebook = PlanNotebook.builder()
 *     .planToHint(new DefaultPlanToHint())
 *     .storage(new InMemoryPlanStorage())
 *     .maxSubtasks(10)
 *     .build();
 *
 * // Create Agent with PlanNotebook (automatically registers tools and hook)
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .planNotebook(planNotebook)
 *     .build();
 *
 * // Or use default PlanNotebook configuration
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .enablePlan()
 *     .build();
 *
 * // Now agent will automatically receive hints before each reasoning step
 * agent.call(msg).block();
 * }</pre>
 *
 * <p><b>Tool Functions:</b> PlanNotebook provides 10 tool functions:
 *
 * <ul>
 *   <li>{@link #createPlan} - Create a new plan
 *   <li>{@link #updatePlanInfo} - Update current plan's name, description, or expected outcome
 *   <li>{@link #reviseCurrentPlan} - Add, revise, or delete subtasks
 *   <li>{@link #updateSubtaskState} - Update subtask state
 *   <li>{@link #finishSubtask} - Mark subtask as done
 *   <li>{@link #viewSubtasks} - View subtask details
 *   <li>{@link #getSubtaskCount} - Get the number of subtasks in current plan
 *   <li>{@link #finishPlan} - Finish or abandon plan
 *   <li>{@link #viewHistoricalPlans} - View historical plans
 *   <li>{@link #recoverHistoricalPlan} - Recover a historical plan
 * </ul>
 */
public class PlanNotebook implements StateModule {

    public static final String DESCRIPTION =
            "The plan-related tools. Activate this tool when you need to execute "
                    + "complex task, e.g. building a website or a game. Once activated, "
                    + "you'll enter the plan mode, where you will be guided to complete "
                    + "the given query by creating and following a plan, and hint message "
                    + "wrapped by <system-hint></system-hint> will guide you to complete "
                    + "the task. If you think the user no longer wants to perform the "
                    + "current task, you need to confirm with the user and call the "
                    + "'finish_plan' function.";

    private Plan currentPlan;
    private final PlanToHint planToHint;
    private final PlanStorage storage;
    private final Integer maxSubtasks;
    private final boolean needUserConfirm;
    private final Map<String, BiConsumer<PlanNotebook, Plan>> changeHooks;

    /** Key prefix for storage, allows multiple instances to coexist in the same session. */
    private String keyPrefix = "planNotebook";

    private PlanNotebook(Builder builder) {
        this.planToHint = builder.planToHint;
        this.storage = builder.storage;
        this.maxSubtasks = builder.maxSubtasks;
        this.needUserConfirm = builder.needUserConfirm;
        this.changeHooks = new ConcurrentHashMap<>();
        if (builder.keyPrefix != null) {
            this.keyPrefix = builder.keyPrefix;
        }
    }

    /**
     * Creates a new builder for constructing PlanNotebook instances.
     *
     * @return A new builder instance with default settings
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== StateModule Implementation ====================

    /**
     * Save PlanNotebook state to the session.
     *
     * <p>Always saves the current state, including when currentPlan is null, to ensure cleared
     * state is persisted.
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // Always save, even when null, to ensure cleared state is persisted
        session.save(sessionKey, keyPrefix + "_state", new PlanNotebookState(currentPlan));
    }

    /**
     * Load PlanNotebook state from the session.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        // Clear existing state first to avoid stale data
        this.currentPlan = null;
        session.get(sessionKey, keyPrefix + "_state", PlanNotebookState.class)
                .ifPresent(state -> this.currentPlan = state.currentPlan());
    }

    /** Builder for constructing PlanNotebook instances with customizable settings. */
    public static class Builder {
        private PlanToHint planToHint = new DefaultPlanToHint();
        private PlanStorage storage = new InMemoryPlanStorage();
        private Integer maxSubtasks = null;
        private boolean needUserConfirm = true;
        private String keyPrefix = null;

        /**
         * Sets the strategy for converting plans to hints.
         *
         * @param planToHint The plan-to-hint converter implementation
         * @return This builder for method chaining
         */
        public Builder planToHint(PlanToHint planToHint) {
            this.planToHint = planToHint;
            return this;
        }

        /**
         * Sets the storage backend for persisting historical plans.
         *
         * @param storage The plan storage implementation
         * @return This builder for method chaining
         */
        public Builder storage(PlanStorage storage) {
            this.storage = storage;
            return this;
        }

        /**
         * Sets the maximum number of subtasks allowed per plan.
         *
         * @param maxSubtasks Maximum subtasks (null for unlimited)
         * @return This builder for method chaining
         */
        public Builder maxSubtasks(int maxSubtasks) {
            this.maxSubtasks = maxSubtasks;
            return this;
        }

        /**
         * Sets whether to include "wait for user confirmation" rule in hints.
         *
         * <p>When enabled (default), hints will include a rule requiring the agent to wait for
         * explicit user confirmation before executing plans. When disabled, the agent may proceed
         * with execution immediately after creating a plan.
         *
         * @param needUserConfirm true to require user confirmation, false to allow immediate
         *     execution
         * @return This builder for method chaining
         */
        public Builder needUserConfirm(boolean needUserConfirm) {
            this.needUserConfirm = needUserConfirm;
            return this;
        }

        /**
         * Sets the key prefix for state storage.
         *
         * <p>Use this when multiple PlanNotebook instances need to coexist in the same session.
         *
         * @param keyPrefix the prefix for storage keys (e.g., "mainPlan", "subPlan")
         * @return This builder for method chaining
         */
        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        /**
         * Builds a new PlanNotebook with the configured settings.
         *
         * @return A new PlanNotebook instance
         */
        public PlanNotebook build() {
            return new PlanNotebook(this);
        }
    }

    // ==================== Tool Functions ====================

    /**
     * Create a plan by given name and sub-tasks.
     *
     * @param name The plan name, should be concise, descriptive and not exceed 10 words
     * @param description The plan description, including the constraints, target and outcome
     * @param expectedOutcome The expected outcome of the plan
     * @param subtasks A list of sequential sub-tasks that make up the plan
     * @return Tool response message
     */
    @Tool(name = "create_plan", description = "Create a plan by given name and sub-tasks")
    public Mono<String> createPlan(
            @ToolParam(
                            name = "name",
                            description =
                                    "The plan name, should be concise, descriptive and not exceed"
                                            + " 10 words")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "The plan description, including the constraints, target and"
                                            + " outcome to be achieved. The description should be"
                                            + " clear, specific and concise, and all the"
                                            + " constraints, target and outcome should be specific"
                                            + " and measurable")
                    String description,
            @ToolParam(
                            name = "expected_outcome",
                            description =
                                    "The expected outcome of the plan, which should be specific,"
                                            + " concrete and measurable")
                    String expectedOutcome,
            @ToolParam(
                            name = "subtasks",
                            description =
                                    "A list of sequential sub-tasks. Each subtask must be an object"
                                            + " with: 'name' (string, required), 'description'"
                                            + " (string), 'expected_outcome' (string). Example:"
                                            + " [{\"name\": \"Calculate area\", \"description\":"
                                            + " \"Multiply length by width\", \"expected_outcome\":"
                                            + " \"Area value\"}]")
                    List<Map<String, Object>> subtasks) {

        // Convert Map objects to SubTask objects
        List<SubTask> subtaskList = new ArrayList<>();
        for (Map<String, Object> subtaskMap : subtasks) {
            subtaskList.add(mapToSubTask(subtaskMap));
        }
        // Validate subtask count against maxSubtasks limit
        // Check BEFORE creating the plan to enforce the configured limit
        if (maxSubtasks != null && subtaskList.size() > maxSubtasks) {
            return Mono.just(
                    String.format(
                            "Cannot create plan: the number of subtasks (%d) exceeds the maximum"
                                    + " limit of %d. Please reduce the number of subtasks.",
                            subtaskList.size(), maxSubtasks));
        }
        Plan plan = new Plan(name, description, expectedOutcome, subtaskList);

        String message;
        if (currentPlan == null) {
            message = String.format("Plan '%s' created successfully.", name);
        } else {
            message =
                    String.format(
                            "The current plan named '%s' is replaced by the newly created plan"
                                    + " named '%s'.",
                            currentPlan.getName(), name);
        }

        currentPlan = plan;
        return triggerPlanChangeHooks().thenReturn(message);
    }

    /**
     * Update the current plan's name, description, or expected outcome.
     *
     * @param name The new plan name (optional, pass null or empty to keep unchanged)
     * @param description The new plan description (optional, pass null or empty to keep unchanged)
     * @param expectedOutcome The new expected outcome (optional, pass null or empty to keep
     *     unchanged)
     * @return Tool response message
     */
    @Tool(
            name = "update_plan_info",
            description =
                    "Update the current plan's name, description, or expected outcome. Pass null or"
                            + " empty string to keep a field unchanged.")
    public Mono<String> updatePlanInfo(
            @ToolParam(
                            name = "name",
                            description =
                                    "The new plan name (optional, pass null or empty to keep"
                                            + " unchanged)")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "The new plan description (optional, pass null or empty to keep"
                                            + " unchanged)")
                    String description,
            @ToolParam(
                            name = "expected_outcome",
                            description =
                                    "The new expected outcome (optional, pass null or empty to keep"
                                            + " unchanged)")
                    String expectedOutcome) {

        validateCurrentPlan();

        StringBuilder changes = new StringBuilder();

        if (name != null && !name.trim().isEmpty()) {
            String oldName = currentPlan.getName();
            currentPlan.setName(name.trim());
            changes.append(String.format("name: '%s' -> '%s'", oldName, name.trim()));
        }

        if (description != null && !description.trim().isEmpty()) {
            currentPlan.setDescription(description.trim());
            if (!changes.isEmpty()) {
                changes.append(", ");
            }
            changes.append("description updated");
        }

        if (expectedOutcome != null && !expectedOutcome.trim().isEmpty()) {
            currentPlan.setExpectedOutcome(expectedOutcome.trim());
            if (!changes.isEmpty()) {
                changes.append(", ");
            }
            changes.append("expected_outcome updated");
        }

        if (changes.isEmpty()) {
            return Mono.just("No changes were made. Please provide at least one field to update.");
        }

        return triggerPlanChangeHooks()
                .thenReturn(
                        String.format(
                                "Plan '%s' updated successfully: %s.",
                                currentPlan.getName(), changes));
    }

    /**
     * Create a plan with SubTask objects (convenience method for tests and Java code).
     *
     * @param name The plan name
     * @param description The plan description
     * @param expectedOutcome The expected outcome
     * @param subtasks The list of SubTask objects
     * @return Tool response message
     */
    public Mono<String> createPlanWithSubTasks(
            String name, String description, String expectedOutcome, List<SubTask> subtasks) {
        return createPlan(name, description, expectedOutcome, subtasksToMaps(subtasks));
    }

    /**
     * Helper method to convert a list of SubTask objects to a list of Maps.
     *
     * @param subtasks List of SubTask objects
     * @return List of Maps
     */
    public static List<Map<String, Object>> subtasksToMaps(List<SubTask> subtasks) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (SubTask subtask : subtasks) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("name", subtask.getName() != null ? subtask.getName() : "Unnamed Subtask");
            map.put(
                    "description",
                    subtask.getDescription() != null ? subtask.getDescription() : "");
            map.put(
                    "expected_outcome",
                    subtask.getExpectedOutcome() != null ? subtask.getExpectedOutcome() : "");
            maps.add(map);
        }
        return maps;
    }

    /**
     * Helper method to convert a SubTask object to a Map.
     *
     * @param subtask SubTask object
     * @return Map representation
     */
    public static Map<String, Object> subtaskToMap(SubTask subtask) {
        if (subtask == null) {
            return null;
        }
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("name", subtask.getName() != null ? subtask.getName() : "Unnamed Subtask");
        map.put("description", subtask.getDescription() != null ? subtask.getDescription() : "");
        map.put(
                "expected_outcome",
                subtask.getExpectedOutcome() != null ? subtask.getExpectedOutcome() : "");
        return map;
    }

    /**
     * Revise the current plan by adding, revising or deleting a sub-task.
     *
     * @param subtaskIdx The index of the sub-task to be revised, starting from 0
     * @param action The action to be performed: add/revise/delete
     * @param subtaskMap The sub-task to be added or revised (required for add/revise)
     * @return Tool response message
     */
    @Tool(
            name = "revise_current_plan",
            description = "Revise the current plan by adding, revising or deleting a sub-task")
    public Mono<String> reviseCurrentPlan(
            @ToolParam(
                            name = "subtask_idx",
                            description =
                                    "The index of the sub-task to be revised, starting from 0")
                    int subtaskIdx,
            @ToolParam(
                            name = "action",
                            description = "The action to be performed: add/revise/delete")
                    String action,
            @ToolParam(
                            name = "subtask",
                            description =
                                    "The sub-task to be added or revised (required for add/revise)")
                    Map<String, Object> subtaskMap) {

        validateCurrentPlan();

        // Convert Map to SubTask object if provided
        SubTask subtask = null;
        if (subtaskMap != null && !subtaskMap.isEmpty()) {
            subtask = mapToSubTask(subtaskMap);
        }

        // Validate action
        if (!List.of("add", "revise", "delete").contains(action)) {
            return Mono.just(
                    String.format(
                            "Invalid action '%s'. Must be one of 'add', 'revise', 'delete'.",
                            action));
        }

        List<SubTask> subtasks = currentPlan.getSubtasks();

        // Validate subtask_idx
        if ("add".equals(action)) {
            if (subtaskIdx < 0 || subtaskIdx > subtasks.size()) {
                return Mono.just(
                        String.format(
                                "Invalid subtask_idx '%d' for action 'add'. Must be between 0 and"
                                        + " %d.",
                                subtaskIdx, subtasks.size()));
            }
            // Validate subtask count against maxSubtasks limit BEFORE adding
            // Use >= because we check before addition: if already at limit, cannot add more
            if (maxSubtasks != null && subtasks.size() >= maxSubtasks) {
                return Mono.just(
                        String.format(
                                "Cannot add more subtasks: the current plan has reached the"
                                        + " maximum limit of %d subtasks. Please delete some"
                                        + " existing subtasks first.",
                                maxSubtasks));
            }
        } else {
            if (subtaskIdx < 0 || subtaskIdx >= subtasks.size()) {
                return Mono.just(
                        String.format(
                                "Invalid subtask_idx '%d' for action '%s'. Must be between 0 and"
                                        + " %d.",
                                subtaskIdx, action, subtasks.size() - 1));
            }
        }

        // Perform action
        return switch (action) {
            case "delete" -> {
                SubTask removed = subtasks.remove(subtaskIdx);
                yield triggerPlanChangeHooks()
                        .thenReturn(
                                String.format(
                                        "Subtask (named '%s') at index %d is deleted successfully.",
                                        removed.getName(), subtaskIdx));
            }
            case "add" -> {
                if (subtask == null) {
                    yield Mono.just("The subtask must be provided when action is 'add'.");
                }
                subtasks.add(subtaskIdx, subtask);
                yield triggerPlanChangeHooks()
                        .thenReturn(
                                String.format(
                                        "New subtask is added successfully at index %d.",
                                        subtaskIdx));
            }
            case "revise" -> {
                if (subtask == null) {
                    yield Mono.just("The subtask must be provided when action is 'revise'.");
                }
                subtasks.set(subtaskIdx, subtask);
                yield triggerPlanChangeHooks()
                        .thenReturn(
                                String.format(
                                        "Subtask at index %d is revised successfully.",
                                        subtaskIdx));
            }
            default -> Mono.just("Invalid action.");
        };
    }

    /**
     * Update the state of a subtask by given index and state.
     *
     * <p>Note: To mark a subtask as done, you SHOULD call {@link #finishSubtask} instead with the
     * specific outcome.
     *
     * @param subtaskIdx The index of the subtask to be updated, starting from 0
     * @param stateStr The new state: todo/in_progress/abandoned
     * @return Tool response message
     */
    @Tool(
            name = "update_subtask_state",
            description = "Update the state of a subtask by given index and state")
    public Mono<String> updateSubtaskState(
            @ToolParam(
                            name = "subtask_idx",
                            description = "The index of the subtask to be updated, starting from 0")
                    int subtaskIdx,
            @ToolParam(name = "state", description = "The new state: todo/in_progress/abandoned")
                    String stateStr) {

        validateCurrentPlan();

        List<SubTask> subtasks = currentPlan.getSubtasks();

        // Validate subtask_idx
        if (subtaskIdx < 0 || subtaskIdx >= subtasks.size()) {
            return Mono.just(
                    String.format(
                            "Invalid subtask_idx '%d'. Must be between 0 and %d.",
                            subtaskIdx, subtasks.size() - 1));
        }

        // Validate state
        SubTaskState state;
        try {
            state = SubTaskState.valueOf(stateStr.toUpperCase());
            if (state == SubTaskState.DONE) {
                return Mono.just(
                        "To mark a subtask as done, you SHOULD call 'finish_subtask' "
                                + "instead with the specific outcome.");
            }
        } catch (IllegalArgumentException e) {
            return Mono.just(
                    String.format(
                            "Invalid state '%s'. Must be one of 'todo', 'in_progress',"
                                    + " 'abandoned'.",
                            stateStr));
        }

        // Validate state transition rules for IN_PROGRESS
        if (state == SubTaskState.IN_PROGRESS) {
            // Check all previous subtasks are done or abandoned
            for (int i = 0; i < subtaskIdx; i++) {
                SubTask st = subtasks.get(i);
                if (st.getState() != SubTaskState.DONE && st.getState() != SubTaskState.ABANDONED) {
                    return Mono.just(
                            String.format(
                                    "Subtask (at index %d) named '%s' is not done yet. "
                                            + "You should finish the previous subtasks first.",
                                    i, st.getName()));
                }
            }

            // Check no other subtask is in_progress
            for (int i = 0; i < subtasks.size(); i++) {
                SubTask st = subtasks.get(i);
                if (st.getState() == SubTaskState.IN_PROGRESS) {
                    return Mono.just(
                            String.format(
                                    "Subtask (at index %d) named '%s' is already 'in_progress'. "
                                            + "You should finish it first before starting another"
                                            + " subtask.",
                                    i, st.getName()));
                }
            }
        }

        subtasks.get(subtaskIdx).setState(state);
        return triggerPlanChangeHooks()
                .thenReturn(
                        String.format(
                                "Subtask at index %d, named '%s' is marked as '%s' successfully.",
                                subtaskIdx, subtasks.get(subtaskIdx).getName(), stateStr));
    }

    /**
     * Label the subtask as done by given index and outcome.
     *
     * @param subtaskIdx The index of the sub-task to be marked as done, starting from 0
     * @param outcome The specific outcome of the sub-task
     * @return Tool response message
     */
    @Tool(
            name = "finish_subtask",
            description = "Label the subtask as done by given index and outcome")
    public Mono<String> finishSubtask(
            @ToolParam(
                            name = "subtask_idx",
                            description =
                                    "The index of the sub-task to be marked as done, starting from"
                                            + " 0")
                    int subtaskIdx,
            @ToolParam(
                            name = "subtask_outcome",
                            description =
                                    "The specific outcome of the sub-task, should exactly match the"
                                        + " expected outcome in the sub-task description. SHOULDN'T"
                                        + " be what you did or general description, e.g. \"I have"
                                        + " searched xxx\", \"I have written the code for xxx\","
                                        + " etc. It SHOULD be the specific data, information, or"
                                        + " path to the file, e.g. \"There are 5 articles about"
                                        + " xxx, they are\\n"
                                        + "- xxx\\n"
                                        + "- xxx\\n"
                                        + "...\"")
                    String outcome) {

        validateCurrentPlan();

        List<SubTask> subtasks = currentPlan.getSubtasks();

        // Validate subtask_idx
        if (subtaskIdx < 0 || subtaskIdx >= subtasks.size()) {
            return Mono.just(
                    String.format(
                            "Invalid subtask_idx '%d'. Must be between 0 and %d.",
                            subtaskIdx, subtasks.size() - 1));
        }

        // Check all previous subtasks are done or abandoned
        for (int i = 0; i < subtaskIdx; i++) {
            SubTask st = subtasks.get(i);
            if (st.getState() != SubTaskState.DONE && st.getState() != SubTaskState.ABANDONED) {
                return Mono.just(
                        String.format(
                                "Cannot finish subtask at index %d because the previous subtask "
                                        + "(at index %d) named '%s' is not done yet. "
                                        + "You should finish the previous subtasks first.",
                                subtaskIdx, i, st.getName()));
            }
        }

        // Finish the subtask
        subtasks.get(subtaskIdx).finish(outcome);

        // Auto activate next subtask if exists
        String message;
        if (subtaskIdx + 1 < subtasks.size()) {
            SubTask nextSubtask = subtasks.get(subtaskIdx + 1);
            nextSubtask.setState(SubTaskState.IN_PROGRESS);
            message =
                    String.format(
                            "Subtask (at index %d) named '%s' is marked as done successfully. "
                                    + "The next subtask (at index %d) named '%s' is activated.",
                            subtaskIdx,
                            subtasks.get(subtaskIdx).getName(),
                            subtaskIdx + 1,
                            nextSubtask.getName());
        } else {
            message =
                    String.format(
                            "Subtask (at index %d) named '%s' is marked as done successfully.",
                            subtaskIdx, subtasks.get(subtaskIdx).getName());
        }

        return triggerPlanChangeHooks().thenReturn(message);
    }

    /**
     * View the details of the sub-tasks by given indexes.
     *
     * @param indexes The indexes of the sub-tasks to be viewed, starting from 0
     * @return Tool response message with subtask details
     */
    @Tool(
            name = "view_subtasks",
            description = "View the details of the sub-tasks by given indexes")
    public Mono<String> viewSubtasks(
            @ToolParam(
                            name = "subtask_idx",
                            description =
                                    "The indexes of the sub-tasks to be viewed, starting from 0")
                    List<Integer> indexes) {

        validateCurrentPlan();

        StringBuilder sb = new StringBuilder();
        List<SubTask> subtasks = currentPlan.getSubtasks();

        for (int idx : indexes) {
            if (idx >= 0 && idx < subtasks.size()) {
                sb.append(
                        String.format(
                                "Subtask at index %d:\n```\n%s\n```\n\n",
                                idx, subtasks.get(idx).toMarkdown(true)));
            } else {
                sb.append(
                        String.format(
                                "Invalid subtask_idx '%d'. Must be between 0 and %d.\n",
                                idx, subtasks.size() - 1));
            }
        }

        return Mono.just(sb.toString());
    }

    /**
     * Get the number of subtasks in the current plan.
     *
     * @return Tool response message with subtask count
     */
    @Tool(
            name = "get_subtask_count",
            description = "Get the number of subtasks in the current plan")
    public Mono<String> getSubtaskCount() {
        if (currentPlan == null) {
            return Mono.just("There is no active plan. Please create a plan first.");
        }

        List<SubTask> subtasks = currentPlan.getSubtasks();
        if (subtasks == null || subtasks.isEmpty()) {
            return Mono.just(
                    String.format("Current plan '%s' has 0 subtask(s).", currentPlan.getName()));
        }

        int total = subtasks.size();
        int done = 0;
        int inProgress = 0;
        int todo = 0;
        int abandoned = 0;

        for (SubTask subtask : subtasks) {
            switch (subtask.getState()) {
                case DONE -> done++;
                case IN_PROGRESS -> inProgress++;
                case TODO -> todo++;
                case ABANDONED -> abandoned++;
            }
        }

        return Mono.just(
                String.format(
                        "Current plan '%s' has %d subtask(s): %d done, %d in_progress, %d todo, %d"
                                + " abandoned.",
                        currentPlan.getName(), total, done, inProgress, todo, abandoned));
    }

    /**
     * Finish the current plan by given outcome, or abandon it.
     *
     * @param stateStr The state to finish the plan: done/abandoned
     * @param outcome The specific outcome of the plan if done, or reason if abandoned
     * @return Tool response message
     */
    @Tool(
            name = "finish_plan",
            description = "Finish the current plan by given outcome, or abandon it")
    public Mono<String> finishPlan(
            @ToolParam(name = "state", description = "The state to finish the plan: done/abandoned")
                    String stateStr,
            @ToolParam(
                            name = "outcome",
                            description =
                                    "The specific outcome of the plan if done, or reason if"
                                            + " abandoned")
                    String outcome) {

        if (currentPlan == null) {
            return Mono.just("There is no plan to finish.");
        }

        PlanState state;
        try {
            state = PlanState.valueOf(stateStr.toUpperCase());
            if (state != PlanState.DONE && state != PlanState.ABANDONED) {
                return Mono.just(
                        String.format(
                                "Invalid state '%s'. Must be 'done' or 'abandoned'.", stateStr));
            }
        } catch (IllegalArgumentException e) {
            return Mono.just(
                    String.format("Invalid state '%s'. Must be 'done' or 'abandoned'.", stateStr));
        }

        currentPlan.finish(state, outcome);

        return storage.addPlan(currentPlan)
                .then(
                        Mono.defer(
                                () -> {
                                    currentPlan = null;
                                    return triggerPlanChangeHooks()
                                            .thenReturn(
                                                    String.format(
                                                            "The current plan is finished"
                                                                    + " successfully as '%s'.",
                                                            stateStr));
                                }));
    }

    /** View the historical plans. */
    @Tool(name = "view_historical_plans", description = "View the historical plans")
    public Mono<String> viewHistoricalPlans() {
        return storage.getPlans()
                .map(
                        plans -> {
                            if (plans.isEmpty()) {
                                return "No historical plans found.";
                            }

                            StringBuilder sb = new StringBuilder();
                            for (Plan plan : plans) {
                                sb.append(
                                        String.format(
                                                "Plan named '%s':\n- ID: %s\n- Created at: %s\n"
                                                        + "- Description: %s\n- State: %s\n\n",
                                                plan.getName(),
                                                plan.getId(),
                                                plan.getCreatedAt(),
                                                plan.getDescription(),
                                                plan.getState().getValue()));
                            }
                            return sb.toString();
                        });
    }

    /**
     * Recover a historical plan by given plan ID.
     *
     * @param planId The ID of the historical plan to be recovered
     * @return Tool response message
     */
    @Tool(
            name = "recover_historical_plan",
            description = "Recover a historical plan by given plan ID")
    public Mono<String> recoverHistoricalPlan(
            @ToolParam(
                            name = "plan_id",
                            description = "The ID of the historical plan to be recovered")
                    String planId) {

        return storage.getPlan(planId)
                .flatMap(
                        historicalPlan -> {
                            if (historicalPlan == null) {
                                return Mono.just(
                                        String.format(
                                                "Cannot find the plan with ID '%s'.", planId));
                            }

                            Mono<Void> saveCurrent = Mono.empty();
                            if (currentPlan != null) {
                                if (currentPlan.getState() != PlanState.DONE) {
                                    currentPlan.finish(
                                            PlanState.ABANDONED,
                                            String.format(
                                                    "The plan execution is interrupted by a new"
                                                            + " plan with ID '%s'.",
                                                    historicalPlan.getId()));
                                }
                                saveCurrent = storage.addPlan(currentPlan);
                            }

                            return saveCurrent.then(
                                    Mono.defer(
                                            () -> {
                                                String message;
                                                if (currentPlan != null) {
                                                    message =
                                                            String.format(
                                                                    "The current plan named '%s' is"
                                                                        + " replaced by the"
                                                                        + " historical plan named"
                                                                        + " '%s' with ID '%s'.",
                                                                    currentPlan.getName(),
                                                                    historicalPlan.getName(),
                                                                    historicalPlan.getId());
                                                } else {
                                                    message =
                                                            String.format(
                                                                    "Historical plan named '%s'"
                                                                            + " with ID '%s' is"
                                                                            + " recovered"
                                                                            + " successfully.",
                                                                    historicalPlan.getName(),
                                                                    historicalPlan.getId());
                                                }

                                                currentPlan = historicalPlan;
                                                return triggerPlanChangeHooks().thenReturn(message);
                                            }));
                        });
    }

    // ==================== Helper Methods ====================

    /**
     * Gets the current hint message based on plan state.
     *
     * <p>This is called internally by the injected hook before each reasoning step to provide
     * contextual guidance to the agent.
     *
     * @return A Mono emitting a USER role message containing the hint, or empty Mono if no hint is
     *     applicable
     */
    public Mono<Msg> getCurrentHint() {
        String hintContent = planToHint.generateHint(currentPlan, this);
        if (hintContent != null && !hintContent.isEmpty()) {
            return Mono.just(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(List.of(TextBlock.builder().text(hintContent).build()))
                            .build());
        }
        return Mono.empty();
    }

    /**
     * Gets the current active plan.
     *
     * @return The current plan, or null if no plan is active
     */
    public Plan getCurrentPlan() {
        return currentPlan;
    }

    /**
     * Checks if user confirmation is required before executing plans.
     *
     * @return true if user confirmation is required, false otherwise
     */
    public boolean isNeedUserConfirm() {
        return needUserConfirm;
    }

    /**
     * Gets the maximum number of subtasks allowed per plan.
     *
     * @return maximum number of subtasks
     */
    public Integer getMaxSubtasks() {
        return maxSubtasks;
    }

    private Mono<Void> triggerPlanChangeHooks() {
        return Flux.fromIterable(changeHooks.values())
                .flatMap(hook -> Mono.fromRunnable(() -> hook.accept(this, currentPlan)))
                .then();
    }

    /**
     * Converts a Map representation of a subtask to a SubTask object.
     *
     * <p>Handles null values by providing defaults: empty strings for description and outcome,
     * "Unnamed Subtask" for missing names.
     *
     * @param subtaskMap Map containing "name", "description", and "expected_outcome" keys
     * @return A SubTask object with validated fields
     */
    private SubTask mapToSubTask(Map<String, Object> subtaskMap) {
        String subtaskName = (String) subtaskMap.get("name");
        String subtaskDesc = (String) subtaskMap.get("description");
        String subtaskOutcome = (String) subtaskMap.get("expected_outcome");

        // Validate and set defaults
        if (subtaskName == null || subtaskName.trim().isEmpty()) {
            subtaskName = "Unnamed Subtask";
        }
        if (subtaskDesc == null) {
            subtaskDesc = "";
        }
        if (subtaskOutcome == null) {
            subtaskOutcome = "";
        }

        return new SubTask(subtaskName, subtaskDesc, subtaskOutcome);
    }

    private void validateCurrentPlan() {
        if (currentPlan == null) {
            throw new IllegalStateException(
                    "The current plan is None, you need to create a plan by calling "
                            + "create_plan() first.");
        }
    }
}
