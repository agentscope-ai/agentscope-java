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
package io.agentscope.examples.plannotebook.dto;

import io.agentscope.core.plan.model.Plan;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for plan response.
 */
public class PlanResponse {

    private String id;
    private String name;
    private String description;
    private String expectedOutcome;
    private String state;
    private String createdAt;
    private List<SubTaskResponse> subtasks;

    public PlanResponse() {
        this.subtasks = new ArrayList<>();
    }

    public static PlanResponse fromPlan(Plan plan) {
        if (plan == null) {
            return null;
        }
        PlanResponse response = new PlanResponse();
        response.setId(plan.getId());
        response.setName(plan.getName());
        response.setDescription(plan.getDescription());
        response.setExpectedOutcome(plan.getExpectedOutcome());
        response.setState(plan.getState().getValue());
        response.setCreatedAt(plan.getCreatedAt());

        List<SubTaskResponse> subtaskResponses = new ArrayList<>();
        if (plan.getSubtasks() != null) {
            for (int i = 0; i < plan.getSubtasks().size(); i++) {
                subtaskResponses.add(SubTaskResponse.fromSubTask(plan.getSubtasks().get(i), i));
            }
        }
        response.setSubtasks(subtaskResponses);

        return response;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<SubTaskResponse> getSubtasks() {
        return subtasks;
    }

    public void setSubtasks(List<SubTaskResponse> subtasks) {
        this.subtasks = subtasks;
    }
}
