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

package io.agentscope.examples.bobatea.business.controller;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import io.agentscope.examples.bobatea.business.model.ApiResponse;
import io.agentscope.examples.bobatea.business.model.FeedbackSolutionRequest;
import io.agentscope.examples.bobatea.business.service.FeedbackService;
import io.agentscope.examples.bobatea.business.util.I18nUtil;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired private FeedbackService feedbackService;

    /**
     * Create feedback record
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Feedback>> createFeedback(@RequestBody Feedback feedback) {
        try {
            Feedback createdFeedback = feedbackService.createFeedback(feedback);
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.create.success"), createdFeedback));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.create.error", e.getMessage())));
        }
    }

    /**
     * Query feedback record by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Feedback>> getFeedbackById(@PathVariable Long id) {
        try {
            Optional<Feedback> feedback = feedbackService.getFeedbackById(id);
            if (feedback.isPresent()) {
                return ResponseEntity.ok(
                        ApiResponse.success(
                                I18nUtil.getMessage("feedback.query.success"), feedback.get()));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.query.error", e.getMessage())));
        }
    }

    /**
     * Query feedback records by user ID
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<Feedback>>> getFeedbacksByUserId(
            @PathVariable Long userId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.query.success"),
                            feedbacks,
                            feedbacks.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.query.error", e.getMessage())));
        }
    }

    /**
     * Query feedback records by order ID
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<List<Feedback>>> getFeedbacksByOrderId(
            @PathVariable String orderId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.query.success"),
                            feedbacks,
                            feedbacks.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.query.error", e.getMessage())));
        }
    }

    /**
     * Query feedback records by feedback type
     */
    @GetMapping("/type/{feedbackType}")
    public ResponseEntity<ApiResponse<List<Feedback>>> getFeedbacksByType(
            @PathVariable Integer feedbackType) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByType(feedbackType);
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.query.success"),
                            feedbacks,
                            feedbacks.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.query.error", e.getMessage())));
        }
    }

    /**
     * Update feedback record
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Feedback>> updateFeedback(
            @PathVariable Long id, @RequestBody Feedback feedback) {
        try {
            feedback.setId(id);
            Feedback updatedFeedback = feedbackService.updateFeedback(feedback);
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.update.success"), updatedFeedback));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.update.error", e.getMessage())));
        }
    }

    /**
     * Update feedback solution
     */
    @PutMapping("/{id}/solution")
    public ResponseEntity<ApiResponse<Void>> updateFeedbackSolution(
            @PathVariable Long id, @RequestBody FeedbackSolutionRequest request) {
        try {
            String solution = request.getSolution();
            boolean success = feedbackService.updateFeedbackSolution(id, solution);
            if (success) {
                return ResponseEntity.ok(
                        ApiResponse.success(
                                I18nUtil.getMessage("feedback.solution.update.success")));
            } else {
                return ResponseEntity.badRequest()
                        .body(
                                ApiResponse.error(
                                        I18nUtil.getMessage("feedback.solution.update.error")));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage(
                                            "feedback.solution.update.error.detail",
                                            e.getMessage())));
        }
    }

    /**
     * Delete feedback record
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFeedback(@PathVariable Long id) {
        try {
            boolean success = feedbackService.deleteFeedback(id);
            if (success) {
                return ResponseEntity.ok(
                        ApiResponse.success(I18nUtil.getMessage("feedback.delete.success")));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(I18nUtil.getMessage("feedback.delete.error")));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage(
                                            "feedback.delete.error.detail", e.getMessage())));
        }
    }

    /**
     * Query all feedback records
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Feedback>>> getAllFeedbacks() {
        try {
            List<Feedback> feedbacks = feedbackService.getAllFeedbacks();
            return ResponseEntity.ok(
                    ApiResponse.success(
                            I18nUtil.getMessage("feedback.query.success"),
                            feedbacks,
                            feedbacks.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.query.error", e.getMessage())));
        }
    }

    /**
     * Count user feedback
     */
    @GetMapping("/user/{userId}/count")
    public ResponseEntity<ApiResponse<Integer>> countFeedbacksByUserId(@PathVariable Long userId) {
        try {
            int count = feedbackService.countFeedbacksByUserId(userId);
            return ResponseEntity.ok(
                    ApiResponse.success(I18nUtil.getMessage("feedback.count.success"), count));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.count.error", e.getMessage())));
        }
    }

    /**
     * Count feedback by type
     */
    @GetMapping("/type/{feedbackType}/count")
    public ResponseEntity<ApiResponse<Integer>> countFeedbacksByType(
            @PathVariable Integer feedbackType) {
        try {
            int count = feedbackService.countFeedbacksByType(feedbackType);
            return ResponseEntity.ok(
                    ApiResponse.success(I18nUtil.getMessage("feedback.count.success"), count));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("feedback.count.error", e.getMessage())));
        }
    }
}
