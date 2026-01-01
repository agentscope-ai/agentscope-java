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

package io.agentscope.examples.bobatea.business.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Feedback Entity Class
 */
public class Feedback {

    private Long id;

    private String orderId;

    @NotNull(message = "User ID cannot be empty")
    private Long userId;

    @NotNull(message = "Feedback type cannot be empty")
    @Min(value = 1, message = "Feedback type must be between 1-4")
    @Max(value = 4, message = "Feedback type must be between 1-4")
    private Integer feedbackType;

    @Min(value = 1, message = "Rating must be between 1-5")
    @Max(value = 5, message = "Rating must be between 1-5")
    private Integer rating;

    @NotBlank(message = "Feedback content cannot be empty")
    private String content;

    private String solution;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructor
    public Feedback() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Feedback(Long userId, Integer feedbackType, String content) {
        this();
        this.userId = userId;
        this.feedbackType = feedbackType;
        this.content = content;
    }

    public Feedback(
            String orderId, Long userId, Integer feedbackType, Integer rating, String content) {
        this();
        this.orderId = orderId;
        this.userId = userId;
        this.feedbackType = feedbackType;
        this.rating = rating;
        this.content = content;
    }

    // Lifecycle callback methods
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter and Setter methods
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Feedback type enum conversion method
    public String getFeedbackTypeText() {
        if (feedbackType == null) return "Unknown";
        switch (feedbackType) {
            case 1:
                return "Product Feedback";
            case 2:
                return "Service Feedback";
            case 3:
                return "Complaint";
            case 4:
                return "Suggestion";
            default:
                return "Unknown";
        }
    }

    // Rating conversion method
    public String getRatingText() {
        if (rating == null) return "Not Rated";
        return rating + " Star(s)";
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
