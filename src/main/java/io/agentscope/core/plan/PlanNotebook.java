/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.plan;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
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
 *   <li><b>Flexible Attachment:</b> Can attach to multiple agents dynamically
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Create PlanNotebook
 * PlanNotebook planNotebook = PlanNotebook.builder().build();
 *
 * // Create Agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .build();
 *
 * // Attach PlanNotebook (registers tools and hook)
 * planNotebook.attachTo(agent);
 *
 * // Now agent will automatically receive hints before each reasoning step
 * agent.call(msg).block();
 * }</pre>
 *
 * <p><b>Tool Functions:</b> PlanNotebook provides 8 tool functions:
 *
 * <ul>
 *   <li>{@link #createPlan} - Create a new plan
 *   <li>{@link #reviseCurrentPlan} - Add, revise, or delete subtasks
 *   <li>{@link #updateSubtaskState} - Update subtask state
 *   <li>{@link #finishSubtask} - Mark subtask as done
 *   <li>{@link #viewSubtasks} - View subtask details
 *   <li>{@link #finishPlan} - Finish or abandon plan
 *   <li>{@link #viewHistoricalPlans} - View historical plans
 *   <li>{@link #recoverHistoricalPlan} - Recover a historical plan
 * </ul>
 */
public class PlanNotebook {

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
    private final Map<String, BiConsumer<PlanNotebook, Plan>> changeHooks;

    // Track attached agents and their hooks for cleanup
    private final Map<ReActAgent, Hook> attachedAgents = new ConcurrentHashMap<>();

    private PlanNotebook(Builder builder) {
        this.planToHint = builder.planToHint;
        this.storage = builder.storage;
        this.maxSubtasks = builder.maxSubtasks;
        this.changeHooks = new ConcurrentHashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for PlanNotebook. */
    public static class Builder {
        private PlanToHint planToHint = new DefaultPlanToHint();
        private PlanStorage storage = new InMemoryPlanStorage();
        private Integer maxSubtasks = null;

        public Builder planToHint(PlanToHint planToHint) {
            this.planToHint = planToHint;
            return this;
        }

        public Builder storage(PlanStorage storage) {
            this.storage = storage;
            return this;
        }

        public Builder maxSubtasks(int maxSubtasks) {
            this.maxSubtasks = maxSubtasks;
            return this;
        }

        public PlanNotebook build() {
            return new PlanNotebook(this);
        }
    }

    // ==================== Agent Integration Methods ====================

    /**
     * Attach this PlanNotebook to a ReActAgent.
     *
     * <p>This method will:
     *
     * <ol>
     *   <li>Register all plan-related tool functions to the agent's toolkit
     *   <li>Install a preReasoning hook that automatically injects plan hints
     *   <li>Track the attachment for later cleanup via {@link #detachFrom(ReActAgent)}
     * </ol>
     *
     * <p>After attachment, the agent will automatically receive contextual hints before each
     * reasoning step, guiding it through plan execution.
     *
     * @param agent The ReActAgent to attach to
     * @return this PlanNotebook for method chaining
     * @throws IllegalArgumentException if agent is null
     * @throws IllegalStateException if already attached to this agent
     */
    public PlanNotebook attachTo(ReActAgent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null");
        }

        if (attachedAgents.containsKey(agent)) {
            throw new IllegalStateException(
                    "PlanNotebook is already attached to this agent. "
                            + "Call detachFrom() first if you want to re-attach.");
        }

        // 1. Register plan tools to agent's toolkit
        agent.getToolkit().registerTool(this);

        // 2. Create and register the hint injection hook
        // Capture the agent reference to avoid shadowing in the hook
        Hook planHintHook =
                new Hook() {
                    @Override
                    public Mono<List<Msg>> preReasoning(Agent a, List<Msg> msgs) {
                        return getCurrentHint()
                                .map(
                                        hintMsg -> {
                                            // Insert hint message at the end (just before
                                            // reasoning)
                                            List<Msg> modifiedMsgs = new ArrayList<>(msgs);
                                            modifiedMsgs.add(hintMsg);
                                            return modifiedMsgs;
                                        })
                                .defaultIfEmpty(msgs); // If no hint, return original msgs
                    }
                };

        // 3. Add hook to agent's hook list using reflection
        agent.getHooks().addLast(planHintHook);

        // 4. Track the attachment
        attachedAgents.put(agent, planHintHook);

        return this;
    }

    /**
     * Detach this PlanNotebook from a ReActAgent.
     *
     * <p>This method will:
     *
     * <ol>
     *   <li>Remove the hint injection hook from the agent
     *   <li>Optionally unregister plan tools from the agent's toolkit
     * </ol>
     *
     * @param agent The ReActAgent to detach from
     * @param unregisterTools Whether to unregister plan tools from toolkit
     * @return this PlanNotebook for method chaining
     * @throws IllegalArgumentException if agent is null
     * @throws IllegalStateException if not attached to this agent
     */
    public PlanNotebook detachFrom(ReActAgent agent, boolean unregisterTools) {
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null");
        }

        Hook hook = attachedAgents.remove(agent);
        if (hook == null) {
            throw new IllegalStateException("PlanNotebook is not attached to this agent.");
        }

        // Remove hook from agent using reflection
        agent.getHooks().remove(hook);

        return this;
    }

    /**
     * Detach from agent without unregistering tools.
     *
     * @param agent The ReActAgent to detach from
     * @return this PlanNotebook for method chaining
     */
    public PlanNotebook detachFrom(ReActAgent agent) {
        return detachFrom(agent, false);
    }

    /**
     * Check if this PlanNotebook is attached to the given agent.
     *
     * @param agent The agent to check
     * @return true if attached, false otherwise
     */
    public boolean isAttachedTo(ReActAgent agent) {
        return attachedAgents.containsKey(agent);
    }

    /** Detach from all attached agents. */
    public void detachFromAll() {
        for (ReActAgent agent : List.copyOf(attachedAgents.keySet())) {
            detachFrom(agent);
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
                                    + "The next subtask named '%s' is activated.",
                            subtaskIdx, subtasks.get(subtaskIdx).getName(), nextSubtask.getName());
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
        String hintContent = planToHint.generateHint(currentPlan);
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
