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
package io.agentscope.core.plan.hint;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;

/**
 * Default implementation of PlanToHint that generates contextual hints based on plan state.
 *
 * <p>Provides prompt templates and logic for generating hints at different stages of plan
 * execution.
 *
 * <p>The generator provides hints for five scenarios:
 *
 * <ul>
 *   <li><b>No Plan:</b> Guides agent to create a plan for complex tasks
 *   <li><b>At the Beginning:</b> All subtasks are TODO
 *   <li><b>Subtask In Progress:</b> One subtask is actively being executed
 *   <li><b>No Subtask In Progress:</b> Some tasks done, but none currently in progress
 *   <li><b>At the End:</b> All subtasks are done or abandoned
 * </ul>
 */
public class DefaultPlanToHint implements PlanToHint {

    // Prompt templates for plan execution guidance

    private static final String HINT_PREFIX = "<system-hint>";
    private static final String HINT_SUFFIX = "</system-hint>";
    private static final String IMPORTANT_RULES_SEPARATOR = "Important Rules: \n";

    private static final String RULE_WAIT_FOR_CONFIRMATION =
            "⚠️ WAIT FOR USER CONFIRMATION:\n"
                    + "- Present the plan and confirm with user before execution\n"
                    + "- If user's request already implies execution intent (e.g., \"execute\","
                    + " \"execute the plan\"), proceed directly without asking\n"
                    + "- Otherwise, ask: \"Should I proceed with this plan?\"\n"
                    + "- Start execution only after user confirms (e.g., \"yes\", \"go ahead\","
                    + " \"proceed\", \"do it\")\n"
                    + "- If user says anything else (questions, modifications, unrelated topics),"
                    + " respond accordingly but DO NOT start execution\n";

    private static final String RULE_SUBTASK_LIMIT =
            "- Subtask Limit: Ensure the plan consists of no more than {max_subtasks} subtasks\n";

    private static final String RULE_COMMON =
            "- Update before processing each subtask: When processing each subtask, call"
                + " get_subtask_count and view_subtasks to confirm the latest information:"
                + " get_subtask_count is used to confirm the total number of subtasks to avoid"
                + " omissions;view_subtasks is used to query subtask information, execute subtasks"
                + " strictly according to the latest information, and pay attention to ignoring the"
                + " original request.\n"
                + "- User May Modify Plan: Users can directly add, edit, or delete subtasks without"
                + " going through you.\n"
                + "- Only focus on the current content: Always follow the latest plan content,"
                + " especially when the original plan conflicts with the latest queried plan,"
                + " follow the latest queried plan without considering the initial requirements.\n"
                + "- Do not modify plan: Do not modify or amend the plan without a clear plan"
                + " modification instruction from user\n"
                + "- Language consistency: Respond to users in the same language as the plan\n";

    private static final String NO_PLAN =
            "If the user's query is complex (e.g. programming a website, game or app), or requires"
                    + " a long chain of steps to complete (e.g. conduct research on a certain topic"
                    + " from different sources), you NEED to create a plan first by calling"
                    + " 'create_plan'. Otherwise, you can directly execute the user's query without"
                    + " planning.\n";

    private static final String AT_THE_BEGINNING =
            "The current plan:\n"
                + "```\n"
                + "{plan}\n"
                + "```\n"
                + "Your options include:\n"
                + "- Mark the first subtask as 'in_progress' by calling 'update_subtask_state' with"
                + " subtask_idx=0 and state='in_progress', and start executing it.\n"
                + "- If the first subtask is not executable, analyze why and what you can do to"
                + " advance the plan, e.g. ask user for more information, revise the plan by"
                + " calling 'revise_current_plan'.\n"
                + "- If the user asks you to do something unrelated to the plan, prioritize the"
                + " completion of user's query first, and then return to the plan afterward.\n"
                + "- If the user no longer wants to perform the current plan, confirm with the user"
                + " and call the 'finish_plan' function.\n";

    private static final String WHEN_A_SUBTASK_IN_PROGRESS =
            "The current plan:\n"
                    + "```\n"
                    + "{plan}\n"
                    + "```\n"
                    + "Now the subtask at index {subtask_idx}, named '{subtask_name}', is "
                    + "'in_progress'. Its details are as follows:\n"
                    + "```\n"
                    + "{subtask}\n"
                    + "```\n"
                    + "Your options include:\n"
                    + "- Go on execute the subtask and get the outcome.\n"
                    + "- Call 'finish_subtask' with the specific outcome if the subtask is "
                    + "finished.\n"
                    + "- Ask the user for more information if you need.\n"
                    + "- Revise the plan by calling 'revise_current_plan' if necessary.\n"
                    + "- If the user asks you to do something unrelated to the plan, "
                    + "prioritize the completion of user's query first, and then return to "
                    + "the plan afterward.\n";

    private static final String WHEN_NO_SUBTASK_IN_PROGRESS =
            "The current plan:\n"
                    + "```\n"
                    + "{plan}\n"
                    + "```\n"
                    + "The first {index} subtasks are done, and there is no subtask "
                    + "'in_progress'. Now Your options include:\n"
                    + "- Mark the next subtask as 'in_progress' by calling "
                    + "'update_subtask_state', and start executing it.\n"
                    + "- Ask the user for more information if you need.\n"
                    + "- Revise the plan by calling 'revise_current_plan' if necessary.\n"
                    + "- If the user asks you to do something unrelated to the plan, "
                    + "prioritize the completion of user's query first, and then return to "
                    + "the plan afterward.\n";

    private static final String AT_THE_END =
            "The current plan:\n"
                    + "```\n"
                    + "{plan}\n"
                    + "```\n"
                    + "All the subtasks are done. Now your options are:\n"
                    + "- Finish the plan by calling 'finish_plan' with the specific "
                    + "outcome, and summarize the whole process and outcome to the user.\n"
                    + "- Revise the plan by calling 'revise_current_plan' if necessary.\n"
                    + "- If the user asks you to do something unrelated to the plan, "
                    + "prioritize the completion of user's query first, and then return to "
                    + "the plan afterward.\n";

    /**
     * Generates a contextual hint message based on the current plan state.
     *
     * <p>The hint guides the agent through different stages of plan execution:
     *
     * <ul>
     *   <li>No plan exists - prompts to create a plan for complex tasks
     *   <li>All subtasks TODO - guides starting the first subtask
     *   <li>Subtask in progress - provides execution options
     *   <li>No subtask in progress - guides selecting next subtask
     *   <li>All subtasks complete - prompts to finish the plan
     * </ul>
     *
     * @param plan The current plan, or null if no plan exists
     * @param planNotebook related planNoteBook configuration
     * @return A formatted hint message wrapped in system-hint tags, or null if no hint is
     *     applicable
     */
    @Override
    public String generateHint(Plan plan, PlanNotebook planNotebook) {
        String hint;
        String confirmationRule =
                planNotebook.isNeedUserConfirm() ? RULE_WAIT_FOR_CONFIRMATION : "";

        if (plan == null) {
            // no plan description with optional append rules
            StringBuilder appendRules = new StringBuilder();
            if (planNotebook.isNeedUserConfirm()) {
                appendRules.append(confirmationRule);
            }
            if (planNotebook.getMaxSubtasks() != null) {
                appendRules.append(
                        RULE_SUBTASK_LIMIT.replace(
                                "{max_subtasks}", String.valueOf(planNotebook.getMaxSubtasks())));
            }
            if (appendRules.isEmpty()) {
                hint = NO_PLAN;
            } else {
                hint = NO_PLAN + IMPORTANT_RULES_SEPARATOR + appendRules;
            }
        } else {
            // Count subtasks by state
            int nTodo = 0;
            int nInProgress = 0;
            int nDone = 0;
            int nAbandoned = 0;
            Integer inProgressIdx = null;

            for (int i = 0; i < plan.getSubtasks().size(); i++) {
                SubTask subtask = plan.getSubtasks().get(i);
                switch (subtask.getState()) {
                    case TODO -> nTodo++;
                    case IN_PROGRESS -> {
                        nInProgress++;
                        inProgressIdx = i;
                    }
                    case DONE -> nDone++;
                    case ABANDONED -> nAbandoned++;
                }
            }

            // Generate hint based on state
            // Check completion status first, then beginning state, then in-progress states
            if (nDone + nAbandoned == plan.getSubtasks().size()) {
                // All subtasks are done or abandoned
                hint = AT_THE_END.replace("{plan}", plan.toMarkdown(false));

            } else if (nInProgress == 0 && nDone == 0) {
                // All subtasks are todo - at the beginning
                hint =
                        AT_THE_BEGINNING.replace("{plan}", plan.toMarkdown(false))
                                + IMPORTANT_RULES_SEPARATOR
                                + confirmationRule
                                + RULE_COMMON;

            } else if (nInProgress > 0 && inProgressIdx != null) {
                // One subtask is in_progress
                SubTask inProgressSubtask = plan.getSubtasks().get(inProgressIdx);
                String subtaskName = inProgressSubtask.getName();
                if (subtaskName == null) {
                    subtaskName = "Unnamed Subtask";
                }
                hint =
                        WHEN_A_SUBTASK_IN_PROGRESS
                                        .replace("{plan}", plan.toMarkdown(false))
                                        .replace("{subtask_idx}", String.valueOf(inProgressIdx))
                                        .replace("{subtask_name}", subtaskName)
                                        .replace("{subtask}", inProgressSubtask.toMarkdown(true))
                                + IMPORTANT_RULES_SEPARATOR
                                + RULE_COMMON;

            } else if (nInProgress == 0 && nDone > 0) {
                // No subtask is in_progress, and some subtasks are done
                hint =
                        WHEN_NO_SUBTASK_IN_PROGRESS
                                        .replace("{plan}", plan.toMarkdown(false))
                                        .replace("{index}", String.valueOf(nDone))
                                + IMPORTANT_RULES_SEPARATOR
                                + RULE_COMMON;

            } else {
                // No relevant hint for this state
                return null;
            }
        }

        return HINT_PREFIX + hint + HINT_SUFFIX;
    }
}
