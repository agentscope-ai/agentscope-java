/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.business;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import io.agentscope.examples.bobatea.business.service.FeedbackService;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FeedbackMcpTools {

    @Autowired private FeedbackService feedbackService;

    /**
     * Create User Feedback
     */
    @Tool(
            name = "feedback-create-feedback",
            description = "Create user feedback record, userId is required")
    public String createFeedback(
            @ToolParam(description = "User ID, required") Long userId,
            @ToolParam(
                            description =
                                    "Feedback type: 1-Product Feedback, 2-Service Feedback,"
                                            + " 3-Complaint, 4-Suggestion")
                    Integer feedbackType,
            @ToolParam(description = "Feedback content") String content,
            @ToolParam(description = "Associated order ID, optional") String orderId,
            @ToolParam(description = "Rating 1-5 stars, optional") Integer rating) {

        try {
            Feedback feedback = new Feedback();
            feedback.setUserId(userId);
            feedback.setFeedbackType(feedbackType);
            feedback.setContent(content);
            if (orderId != null && !orderId.trim().isEmpty()) {
                feedback.setOrderId(orderId);
            }
            if (rating != null) {
                feedback.setRating(rating);
            }

            Feedback createdFeedback = feedbackService.createFeedback(feedback);
            return String.format(
                    "Feedback record created successfully! Feedback ID: %d, User ID: %d, Feedback"
                            + " Type: %s, Content: %s",
                    createdFeedback.getId(),
                    createdFeedback.getUserId(),
                    createdFeedback.getFeedbackTypeText(),
                    createdFeedback.getContent());
        } catch (Exception e) {
            return "Failed to create feedback record: " + e.getMessage();
        }
    }

    /**
     * Query Feedback Records by User ID
     */
    @Tool(name = "feedback-get-feedback-by-user", description = "Query feedback records by user ID")
    public String getFeedbacksByUserId(@ToolParam(description = "User ID") Long userId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            if (feedbacks.isEmpty()) {
                return "No feedback records for this user";
            }

            StringBuilder result = new StringBuilder();
            result.append(
                    String.format(
                            "User %d feedback records (total %d):\n", userId, feedbacks.size()));

            for (Feedback feedback : feedbacks) {
                result.append(
                        String.format(
                                "- Feedback ID: %d, Type: %s, Rating: %s, Content: %s, Time: %s\n",
                                feedback.getId(),
                                feedback.getFeedbackTypeText(),
                                feedback.getRatingText(),
                                feedback.getContent(),
                                feedback.getCreatedAt()));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to query user feedback records: " + e.getMessage();
        }
    }

    /**
     * Query Feedback Records by Order ID
     */
    @Tool(
            name = "feedback-get-feedback-by-order",
            description = "Query feedback records by order ID")
    public String getFeedbacksByOrderId(@ToolParam(description = "Order ID") String orderId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            if (feedbacks.isEmpty()) {
                return "No feedback records for this order";
            }

            StringBuilder result = new StringBuilder();
            result.append(
                    String.format(
                            "Order %s feedback records (total %d):\n", orderId, feedbacks.size()));

            for (Feedback feedback : feedbacks) {
                result.append(
                        String.format(
                                "- Feedback ID: %d, User ID: %d, Type: %s, Rating: %s, Content: %s,"
                                        + " Time: %s\n",
                                feedback.getId(),
                                feedback.getUserId(),
                                feedback.getFeedbackTypeText(),
                                feedback.getRatingText(),
                                feedback.getContent(),
                                feedback.getCreatedAt()));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to query order feedback records: " + e.getMessage();
        }
    }

    /**
     * Update Feedback Solution
     */
    @Tool(name = "feedback-update-solution", description = "Update feedback solution")
    public String updateFeedbackSolution(
            @ToolParam(description = "Feedback ID") Long feedbackId,
            @ToolParam(description = "Solution") String solution) {
        try {
            boolean success = feedbackService.updateFeedbackSolution(feedbackId, solution);
            if (success) {
                return String.format(
                        "Feedback ID %d solution updated successfully: %s", feedbackId, solution);
            } else {
                return String.format("Failed to update solution for Feedback ID %d", feedbackId);
            }
        } catch (Exception e) {
            return "Failed to update feedback solution: " + e.getMessage();
        }
    }

    /**
     * Get feedback type text
     */
    private String getFeedbackTypeText(Integer feedbackType) {
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
}
