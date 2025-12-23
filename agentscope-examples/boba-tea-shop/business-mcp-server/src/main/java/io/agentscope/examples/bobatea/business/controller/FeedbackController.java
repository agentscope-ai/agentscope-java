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

package io.agentscope.examples.bobatea.business.controller;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import io.agentscope.examples.bobatea.business.service.FeedbackService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public ResponseEntity<Map<String, Object>> createFeedback(@RequestBody Feedback feedback) {
        Map<String, Object> response = new HashMap<>();
        try {
            Feedback createdFeedback = feedbackService.createFeedback(feedback);
            response.put("success", true);
            response.put("message", "反馈记录创建成功");
            response.put("data", createdFeedback);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "反馈记录创建失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Query feedback record by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getFeedbackById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<Feedback> feedback = feedbackService.getFeedbackById(id);
            if (feedback.isPresent()) {
                response.put("success", true);
                response.put("message", "查询成功");
                response.put("data", feedback.get());
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "反馈记录不存在");
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Query feedback records by user ID
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getFeedbacksByUserId(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            response.put("success", true);
            response.put("message", "查询成功");
            response.put("data", feedbacks);
            response.put("count", feedbacks.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Query feedback records by order ID
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<Map<String, Object>> getFeedbacksByOrderId(@PathVariable String orderId) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            response.put("success", true);
            response.put("message", "查询成功");
            response.put("data", feedbacks);
            response.put("count", feedbacks.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Query feedback records by feedback type
     */
    @GetMapping("/type/{feedbackType}")
    public ResponseEntity<Map<String, Object>> getFeedbacksByType(
            @PathVariable Integer feedbackType) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByType(feedbackType);
            response.put("success", true);
            response.put("message", "查询成功");
            response.put("data", feedbacks);
            response.put("count", feedbacks.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Update feedback record
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateFeedback(
            @PathVariable Long id, @RequestBody Feedback feedback) {
        Map<String, Object> response = new HashMap<>();
        try {
            feedback.setId(id);
            Feedback updatedFeedback = feedbackService.updateFeedback(feedback);
            response.put("success", true);
            response.put("message", "反馈记录更新成功");
            response.put("data", updatedFeedback);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "反馈记录更新失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Update feedback solution
     */
    @PutMapping("/{id}/solution")
    public ResponseEntity<Map<String, Object>> updateFeedbackSolution(
            @PathVariable Long id, @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String solution = request.get("solution");
            boolean success = feedbackService.updateFeedbackSolution(id, solution);
            if (success) {
                response.put("success", true);
                response.put("message", "反馈解决方案更新成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "反馈解决方案更新失败");
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "反馈解决方案更新失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Delete feedback record
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteFeedback(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = feedbackService.deleteFeedback(id);
            if (success) {
                response.put("success", true);
                response.put("message", "反馈记录删除成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "反馈记录删除失败");
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "反馈记录删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Query all feedback records
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllFeedbacks() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Feedback> feedbacks = feedbackService.getAllFeedbacks();
            response.put("success", true);
            response.put("message", "查询成功");
            response.put("data", feedbacks);
            response.put("count", feedbacks.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Count user feedback
     */
    @GetMapping("/user/{userId}/count")
    public ResponseEntity<Map<String, Object>> countFeedbacksByUserId(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            int count = feedbackService.countFeedbacksByUserId(userId);
            response.put("success", true);
            response.put("message", "统计成功");
            response.put("data", count);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "统计失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Count feedback by type
     */
    @GetMapping("/type/{feedbackType}/count")
    public ResponseEntity<Map<String, Object>> countFeedbacksByType(
            @PathVariable Integer feedbackType) {
        Map<String, Object> response = new HashMap<>();
        try {
            int count = feedbackService.countFeedbacksByType(feedbackType);
            response.put("success", true);
            response.put("message", "统计成功");
            response.put("data", count);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "统计失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
