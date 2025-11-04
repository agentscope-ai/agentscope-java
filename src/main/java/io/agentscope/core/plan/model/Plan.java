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
import java.util.List;
import java.util.UUID;

/**
 * Represents a plan containing a sequence of subtasks.
 *
 * <p>A plan breaks down a complex task into manageable subtasks with clear goals and expected
 * outcomes. It tracks the overall progress and provides a structured approach to task execution.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * Plan plan = new Plan(
 *     "Build E-commerce Website",
 *     "Build a complete e-commerce platform with authentication and payment",
 *     "Fully functional website deployed online",
 *     List.of(
 *         new SubTask("Setup", "Initialize project", "Project ready"),
 *         new SubTask("Auth", "Implement authentication", "Users can login"),
 *         new SubTask("Cart", "Implement shopping cart", "Cart works")
 *     )
 * );
 * }</pre>
 */
public class Plan {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @JsonIgnore private String id = UUID.randomUUID().toString();

    private String name;
    private String description;
    private String expectedOutcome;
    private List<SubTask> subtasks;

    @JsonIgnore private String createdAt;

    @JsonIgnore private PlanState state = PlanState.TODO;

    @JsonIgnore private String finishedAt;

    @JsonIgnore private String outcome;

    /** Default constructor for deserialization. */
    public Plan() {
        this.createdAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Create a new plan.
     *
     * @param name The plan name (should be concise, not exceed 10 words)
     * @param description The plan description including constraints and targets
     * @param expectedOutcome The expected outcome, specific and measurable
     * @param subtasks The list of subtasks that make up the plan
     */
    public Plan(String name, String description, String expectedOutcome, List<SubTask> subtasks) {
        this();
        this.name = name;
        this.description = description;
        this.expectedOutcome = expectedOutcome;
        this.subtasks = subtasks;
    }

    /**
     * Mark the plan as finished.
     *
     * @param state The final state (DONE or ABANDONED)
     * @param outcome The actual outcome or reason for abandoning
     */
    public void finish(PlanState state, String outcome) {
        this.state = state;
        this.outcome = outcome;
        this.finishedAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Convert to markdown representation.
     *
     * @param detailed Whether to include detailed information for subtasks
     * @return Markdown string representation
     */
    public String toMarkdown(boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("\n");
        sb.append("**Description**: ").append(description).append("\n");
        sb.append("**Expected Outcome**: ").append(expectedOutcome).append("\n");
        sb.append("**State**: ").append(state.getValue()).append("\n");
        sb.append("**Created At**: ").append(createdAt).append("\n");
        sb.append("## Subtasks\n");

        for (SubTask subtask : subtasks) {
            sb.append(subtask.toMarkdown(detailed)).append("\n");
        }

        return sb.toString();
    }

    // Getters and Setters

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

    public List<SubTask> getSubtasks() {
        return subtasks;
    }

    public void setSubtasks(List<SubTask> subtasks) {
        this.subtasks = subtasks;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public PlanState getState() {
        return state;
    }

    public void setState(PlanState state) {
        this.state = state;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }
}
