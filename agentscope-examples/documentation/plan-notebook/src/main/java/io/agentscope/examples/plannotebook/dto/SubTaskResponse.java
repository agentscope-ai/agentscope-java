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

import io.agentscope.core.plan.model.SubTask;

/**
 * DTO for subtask response.
 */
public class SubTaskResponse {

    private int index;
    private String name;
    private String description;
    private String expectedOutcome;
    private String state;
    private String outcome;
    private String createdAt;

    public SubTaskResponse() {}

    public static SubTaskResponse fromSubTask(SubTask subtask, int index) {
        SubTaskResponse response = new SubTaskResponse();
        response.setIndex(index);
        response.setName(subtask.getName());
        response.setDescription(subtask.getDescription());
        response.setExpectedOutcome(subtask.getExpectedOutcome());
        response.setState(subtask.getState().getValue());
        response.setOutcome(subtask.getOutcome());
        response.setCreatedAt(subtask.getCreatedAt());
        return response;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
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

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
