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
package io.agentscope.core.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a subtask within a plan.
 *
 * <p>A subtask is a unit of work with a specific goal and expected outcome. It has a state that
 * tracks its progress through the execution lifecycle.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * SubTask task = new SubTask(
 *     "Setup project",
 *     "Initialize project structure with proper directory layout",
 *     "Project scaffolding completed"
 * );
 *
 * task.setState(SubTaskState.IN_PROGRESS);
 * // ... execute task
 * task.finish("Project initialized with src/, test/, and docs/ directories");
 * }</pre>
 */
public class SubTask {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String name;
    private String description;
    private String expectedOutcome;

    @JsonIgnore private String outcome;

    private SubTaskState state = SubTaskState.TODO;
    private String createdAt;

    @JsonIgnore private String finishedAt;

    /** Default constructor for deserialization. */
    public SubTask() {
        this.createdAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Create a new subtask.
     *
     * @param name The subtask name (should be concise, not exceed 10 words)
     * @param description The detailed description including constraints and targets
     * @param expectedOutcome The expected outcome, specific and measurable
     */
    public SubTask(String name, String description, String expectedOutcome) {
        this();
        this.name = name;
        this.description = description;
        this.expectedOutcome = expectedOutcome;
    }

    /**
     * Mark the subtask as finished with the actual outcome.
     *
     * @param outcome The actual outcome achieved
     */
    public void finish(String outcome) {
        this.state = SubTaskState.DONE;
        this.outcome = outcome;
        this.finishedAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Convert to one-line markdown representation.
     *
     * @return One-line markdown string
     */
    public String toOneLineMarkdown() {
        String statusPrefix =
                switch (state) {
                    case TODO -> "- [ ]";
                    case IN_PROGRESS -> "- [ ] [WIP]";
                    case DONE -> "- [x]";
                    case ABANDONED -> "- [ ] [Abandoned]";
                };
        String displayName = (name != null) ? name : "Unnamed Subtask";
        return statusPrefix + " " + displayName;
    }

    /**
     * Convert to markdown representation.
     *
     * @param detailed Whether to include detailed information
     * @return Markdown string representation
     */
    public String toMarkdown(boolean detailed) {
        if (!detailed) {
            return toOneLineMarkdown();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(toOneLineMarkdown()).append("\n");
        sb.append("\t- Created At: ").append(createdAt != null ? createdAt : "N/A").append("\n");
        sb.append("\t- Description: ")
                .append(description != null ? description : "N/A")
                .append("\n");
        sb.append("\t- Expected Outcome: ")
                .append(expectedOutcome != null ? expectedOutcome : "N/A")
                .append("\n");
        sb.append("\t- State: ").append(state.getValue());

        if (state == SubTaskState.DONE) {
            sb.append("\n");
            sb.append("\t- Finished At: ")
                    .append(finishedAt != null ? finishedAt : "N/A")
                    .append("\n");
            sb.append("\t- Actual Outcome: ").append(outcome != null ? outcome : "N/A");
        }

        return sb.toString();
    }

    // Getters and Setters

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

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public SubTaskState getState() {
        return state;
    }

    public void setState(SubTaskState state) {
        this.state = state;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
