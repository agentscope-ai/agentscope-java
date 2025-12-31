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
package io.agentscope.examples.plannotebook.controller;

import io.agentscope.examples.plannotebook.dto.PlanResponse;
import io.agentscope.examples.plannotebook.dto.SubTaskRequest;
import io.agentscope.examples.plannotebook.service.PlanService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Controller for plan management API.
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /**
     * SSE stream for plan state changes.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlanResponse> getPlanStream() {
        return planService.getPlanStream();
    }

    /**
     * Get current plan state.
     */
    @GetMapping
    public PlanResponse getCurrentPlan() {
        return planService.getCurrentPlan();
    }

    /**
     * Update current plan's name, description, or expected outcome.
     * Request body: { "name": "...", "description": "...", "expectedOutcome": "..." }
     */
    @PutMapping
    public Mono<String> updatePlanInfo(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        String expectedOutcome = request.get("expectedOutcome");
        return planService.updatePlanInfo(name, description, expectedOutcome);
    }

    /**
     * Add a subtask at the specified index.
     * Request body: { "index": 0, "name": "...", "description": "...", "expectedOutcome": "..." }
     */
    @PostMapping("/subtasks")
    public Mono<String> addSubtask(@RequestBody Map<String, Object> request) {
        int index = ((Number) request.get("index")).intValue();
        SubTaskRequest subtask =
                new SubTaskRequest(
                        (String) request.get("name"),
                        (String) request.get("description"),
                        (String) request.get("expectedOutcome"));
        return planService.addSubtask(index, subtask);
    }

    /**
     * Revise a subtask at the specified index.
     */
    @PutMapping("/subtasks/{index}")
    public Mono<String> reviseSubtask(
            @PathVariable int index, @RequestBody SubTaskRequest subtask) {
        return planService.reviseSubtask(index, subtask);
    }

    /**
     * Delete a subtask at the specified index.
     */
    @DeleteMapping("/subtasks/{index}")
    public Mono<String> deleteSubtask(@PathVariable int index) {
        return planService.deleteSubtask(index);
    }

    /**
     * Update subtask state.
     * Request body: { "state": "in_progress" | "todo" | "abandoned" }
     */
    @PatchMapping("/subtasks/{index}/state")
    public Mono<String> updateSubtaskState(
            @PathVariable int index, @RequestBody Map<String, String> request) {
        String state = request.get("state");
        return planService.updateSubtaskState(index, state);
    }

    /**
     * Finish a subtask with outcome.
     * Request body: { "outcome": "..." }
     */
    @PostMapping("/subtasks/{index}/finish")
    public Mono<String> finishSubtask(
            @PathVariable int index, @RequestBody Map<String, String> request) {
        String outcome = request.get("outcome");
        return planService.finishSubtask(index, outcome);
    }

    /**
     * Finish the current plan.
     * Request body: { "state": "done" | "abandoned", "outcome": "..." }
     */
    @PostMapping("/finish")
    public Mono<String> finishPlan(@RequestBody Map<String, String> request) {
        String state = request.get("state");
        String outcome = request.get("outcome");
        return planService.finishPlan(state, outcome);
    }
}
