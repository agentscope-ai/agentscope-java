/*
 * Copyright 2025 the original author or authors.
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

package io.agentscope.examples.bobatea.business.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 反馈实体类
 */
public class Feedback {

    private Long id;

    private String orderId;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "反馈类型不能为空")
    @Min(value = 1, message = "反馈类型必须在1-4之间")
    @Max(value = 4, message = "反馈类型必须在1-4之间")
    private Integer feedbackType;

    @Min(value = 1, message = "评分必须在1-5之间")
    @Max(value = 5, message = "评分必须在1-5之间")
    private Integer rating;

    @NotBlank(message = "反馈内容不能为空")
    private String content;

    private String solution;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // 构造函数
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

    // 生命周期回调方法
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter和Setter方法
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

    // 反馈类型枚举转换方法
    public String getFeedbackTypeText() {
        if (feedbackType == null) return "未知";
        switch (feedbackType) {
            case 1:
                return "产品反馈";
            case 2:
                return "服务反馈";
            case 3:
                return "投诉";
            case 4:
                return "建议";
            default:
                return "未知";
        }
    }

    // 评分转换方法
    public String getRatingText() {
        if (rating == null) return "未评分";
        return rating + "星";
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
