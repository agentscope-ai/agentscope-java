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

    /**
     * Gets the unique identifier of this plan.
     *
     * @return The plan ID (UUID format)
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the unique identifier of this plan.
     *
     * @param id The plan ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Gets the name of this plan.
     *
     * @return The plan name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this plan.
     *
     * @param name The plan name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the description of this plan.
     *
     * @return The plan description including constraints and targets
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of this plan.
     *
     * @param description The plan description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Gets the expected outcome of this plan.
     *
     * @return The expected outcome (specific and measurable)
     */
    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    /**
     * Sets the expected outcome of this plan.
     *
     * @param expectedOutcome The expected outcome
     */
    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome;
    }

    /**
     * Gets the list of subtasks in this plan.
     *
     * @return The subtasks list
     */
    public List<SubTask> getSubtasks() {
        return subtasks;
    }

    /**
     * Sets the list of subtasks for this plan.
     *
     * @param subtasks The subtasks list
     */
    public void setSubtasks(List<SubTask> subtasks) {
        this.subtasks = subtasks;
    }

    /**
     * Gets the creation timestamp of this plan.
     *
     * @return The creation time (formatted as "yyyy-MM-dd HH:mm:ss")
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp of this plan.
     *
     * @param createdAt The creation time
     */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Gets the current state of this plan.
     *
     * @return The plan state (TODO, IN_PROGRESS, DONE, or ABANDONED)
     */
    public PlanState getState() {
        return state;
    }

    /**
     * Sets the state of this plan.
     *
     * @param state The plan state
     */
    public void setState(PlanState state) {
        this.state = state;
    }

    /**
     * Gets the timestamp when this plan was finished.
     *
     * @return The finish time, or null if not finished yet
     */
    public String getFinishedAt() {
        return finishedAt;
    }

    /**
     * Sets the timestamp when this plan was finished.
     *
     * @param finishedAt The finish time
     */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    /**
     * Gets the actual outcome of this plan.
     *
     * @return The actual outcome (if done) or reason for abandoning
     */
    public String getOutcome() {
        return outcome;
    }

    /**
     * Sets the actual outcome of this plan.
     *
     * @param outcome The actual outcome or reason
     */
    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }
}
