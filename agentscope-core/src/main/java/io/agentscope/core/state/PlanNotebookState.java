/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.state;

import io.agentscope.core.plan.model.Plan;

/**
 * State record for PlanNotebook.
 *
 * <p>This record captures the current plan state for persistence. It wraps the existing {@link
 * Plan} object which contains the full plan structure including subtasks.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Plan currentPlan = planNotebook.getCurrentPlan();
 * if (currentPlan != null) {
 *     PlanNotebookState state = new PlanNotebookState(currentPlan);
 *     session.save(sessionKey, "planNotebook_state", state);
 * }
 *
 * // Later, restore the state
 * Optional<PlanNotebookState> loaded = session.get(sessionKey, "planNotebook_state", PlanNotebookState.class);
 * }</pre>
 *
 * @param currentPlan the current active plan, may be null if no plan is active
 * @see State
 * @see io.agentscope.core.plan.PlanNotebook
 * @see Plan
 */
public record PlanNotebookState(Plan currentPlan) implements State {}
