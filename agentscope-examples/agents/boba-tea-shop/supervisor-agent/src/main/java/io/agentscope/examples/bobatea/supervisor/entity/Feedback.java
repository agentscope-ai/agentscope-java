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

package io.agentscope.examples.bobatea.supervisor.entity;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Feedback entity representing customer feedback in the system
 **/
public class Feedback {
    private Long id;
    private String orderId;
    private Long userId;
    private Integer feedbackType;
    private Integer rating;
    private String content;
    private String solution;
    private Date createdAt;
    private Date updatedAt;

    // Default constructor
    public Feedback() {}

    // Constructor with all fields
    public Feedback(
            Long id,
            String orderId,
            Long userId,
            Integer feedbackType,
            Integer rating,
            String content,
            String solution,
            Date createdAt,
            Date updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.feedbackType = feedbackType;
        this.rating = rating;
        this.content = content;
        this.solution = solution;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getFeedbackType() {
        return feedbackType;
    }

    public void setFeedbackType(Integer feedbackType) {
        this.feedbackType = feedbackType;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSolution() {
        return solution;
    }

    public void setSolution(String solution) {
        this.solution = solution;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Converts the feedback to a formatted string representation
     * @return formatted string with time, user, and evaluation content
     */
    public String toFormattedString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("time: ").append(sdf.format(this.createdAt)).append("\n");
        sb.append("user: ").append(this.userId).append("\n");
        sb.append("evaluation: ").append(this.content);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Feedback{"
                + "id="
                + id
                + ", orderId='"
                + orderId
                + '\''
                + ", userId="
                + userId
                + ", feedbackType="
                + feedbackType
                + ", rating="
                + rating
                + ", content='"
                + content
                + '\''
                + ", solution='"
                + solution
                + '\''
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
