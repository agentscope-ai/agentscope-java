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
package io.agentscope.examples.plannotebook.service;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.examples.plannotebook.dto.PlanResponse;
import io.agentscope.examples.plannotebook.dto.SubTaskRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Service for managing Plan operations.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    // SSE sink for broadcasting plan changes
    private final Sinks.Many<PlanResponse> planSink =
            Sinks.many().multicast().onBackpressureBuffer();

    private PlanNotebook planNotebook;

    /**
     * Set the PlanNotebook instance. Called by AgentService during initialization.
     */
    public void setPlanNotebook(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
    }

    /**
     * Get the PlanNotebook instance.
     */
    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    /**
     * Get SSE stream for plan changes.
     */
    public Flux<PlanResponse> getPlanStream() {
        // Send current plan state immediately, then stream updates
        return Flux.concat(
                Mono.fromCallable(
                        () -> {
                            PlanResponse response = getCurrentPlan();
                            return response != null ? response : new PlanResponse();
                        }),
                planSink.asFlux());
    }

    /**
     * Broadcast plan change to all subscribers.
     */
    public void broadcastPlanChange() {
        PlanResponse response = getCurrentPlan();
        // Reactor Sink doesn't allow null values, send empty PlanResponse instead
        if (response == null) {
            response = new PlanResponse();
        }
        planSink.tryEmitNext(response);
        log.debug(
                "Plan change broadcasted: {}",
                response.getName() != null ? response.getName() : "null");
    }

    /**
     * Get current plan state.
     */
    public PlanResponse getCurrentPlan() {
        if (planNotebook == null) {
            return null;
        }
        return PlanResponse.fromPlan(planNotebook.getCurrentPlan());
    }

    /**
     * Add a subtask at the specified index.
     */
    public Mono<String> addSubtask(int index, SubTaskRequest subtask) {
        Map<String, Object> subtaskMap = new HashMap<>();
        subtaskMap.put("name", subtask.getName());
        subtaskMap.put("description", subtask.getDescription());
        subtaskMap.put("expected_outcome", subtask.getExpectedOutcome());

        return planNotebook
                .reviseCurrentPlan(index, "add", subtaskMap)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Revise a subtask at the specified index.
     */
    public Mono<String> reviseSubtask(int index, SubTaskRequest subtask) {
        Map<String, Object> subtaskMap = new HashMap<>();
        subtaskMap.put("name", subtask.getName());
        subtaskMap.put("description", subtask.getDescription());
        subtaskMap.put("expected_outcome", subtask.getExpectedOutcome());

        return planNotebook
                .reviseCurrentPlan(index, "revise", subtaskMap)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Delete a subtask at the specified index.
     */
    public Mono<String> deleteSubtask(int index) {
        return planNotebook
                .reviseCurrentPlan(index, "delete", null)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Update subtask state.
     */
    public Mono<String> updateSubtaskState(int index, String state) {
        return planNotebook
                .updateSubtaskState(index, state)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Finish a subtask with outcome.
     */
    public Mono<String> finishSubtask(int index, String outcome) {
        return planNotebook
                .finishSubtask(index, outcome)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Finish the current plan.
     */
    public Mono<String> finishPlan(String state, String outcome) {
        return planNotebook.finishPlan(state, outcome).doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Update the current plan's name, description, or expected outcome.
     */
    public Mono<String> updatePlanInfo(String name, String description, String expectedOutcome) {
        return planNotebook
                .updatePlanInfo(name, description, expectedOutcome)
                .doOnSuccess(result -> broadcastPlanChange());
    }

    /**
     * Create a new plan.
     */
    public Mono<String> createPlan(
            String name,
            String description,
            String expectedOutcome,
            List<Map<String, Object>> subtasks) {
        return planNotebook
                .createPlan(name, description, expectedOutcome, subtasks)
                .doOnSuccess(result -> broadcastPlanChange());
    }
}
