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

package io.agentscope.examples.bobatea.business.service;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import io.agentscope.examples.bobatea.business.mapper.FeedbackMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private static final Logger logger = LoggerFactory.getLogger(FeedbackService.class);

    @Autowired private FeedbackMapper feedbackMapper;

    /**
     * Create feedback record
     */
    @Transactional
    public Feedback createFeedback(Feedback feedback) {
        try {
            logger.info(
                    "Creating feedback record, user ID: {}, feedback type: {}",
                    feedback.getUserId(),
                    feedback.getFeedbackType());

            // Set creation time
            feedback.onCreate();

            int result = feedbackMapper.insert(feedback);
            if (result > 0) {
                logger.info("Feedback record created successfully, ID: {}", feedback.getId());
                return feedback;
            } else {
                logger.error("Failed to create feedback record");
                throw new RuntimeException("Failed to create feedback record");
            }
        } catch (Exception e) {
            logger.error("Error occurred while creating feedback record", e);
            throw new RuntimeException("Failed to create feedback record: " + e.getMessage());
        }
    }

    /**
     * Query feedback record by ID
     */
    public Optional<Feedback> getFeedbackById(Long id) {
        try {
            logger.info("Querying feedback record, ID: {}", id);
            Feedback feedback = feedbackMapper.selectById(id);
            return Optional.ofNullable(feedback);
        } catch (Exception e) {
            logger.error("Error occurred while querying feedback record, ID: {}", id, e);
            throw new RuntimeException("Failed to query feedback record: " + e.getMessage());
        }
    }

    /**
     * Query feedback records by user ID
     */
    public List<Feedback> getFeedbacksByUserId(Long userId) {
        try {
            logger.info("Querying user feedback records, user ID: {}", userId);
            return feedbackMapper.selectByUserId(userId);
        } catch (Exception e) {
            logger.error(
                    "Error occurred while querying user feedback records, user ID: {}", userId, e);
            throw new RuntimeException("Failed to query user feedback records: " + e.getMessage());
        }
    }

    /**
     * Query feedback records by order ID
     */
    public List<Feedback> getFeedbacksByOrderId(String orderId) {
        try {
            logger.info("Querying order feedback records, order ID: {}", orderId);
            return feedbackMapper.selectByOrderId(orderId);
        } catch (Exception e) {
            logger.error(
                    "Error occurred while querying order feedback records, order ID: {}",
                    orderId,
                    e);
            throw new RuntimeException("Failed to query order feedback records: " + e.getMessage());
        }
    }

    /**
     * Query feedback records by feedback type
     */
    public List<Feedback> getFeedbacksByType(Integer feedbackType) {
        try {
            logger.info("Querying feedback type records, type: {}", feedbackType);
            return feedbackMapper.selectByFeedbackType(feedbackType);
        } catch (Exception e) {
            logger.error(
                    "Error occurred while querying feedback type records, type: {}",
                    feedbackType,
                    e);
            throw new RuntimeException("Failed to query feedback type records: " + e.getMessage());
        }
    }

    /**
     * Update feedback record
     */
    @Transactional
    public Feedback updateFeedback(Feedback feedback) {
        try {
            logger.info("Updating feedback record, ID: {}", feedback.getId());

            // Set update time
            feedback.onUpdate();

            int result = feedbackMapper.update(feedback);
            if (result > 0) {
                logger.info("Feedback record updated successfully, ID: {}", feedback.getId());
                return feedback;
            } else {
                logger.error("Failed to update feedback record, ID: {}", feedback.getId());
                throw new RuntimeException("Failed to update feedback record");
            }
        } catch (Exception e) {
            logger.error(
                    "Error occurred while updating feedback record, ID: {}", feedback.getId(), e);
            throw new RuntimeException("Failed to update feedback record: " + e.getMessage());
        }
    }

    /**
     * Update feedback solution
     */
    @Transactional
    public boolean updateFeedbackSolution(Long id, String solution) {
        try {
            logger.info("Updating feedback solution, ID: {}, solution: {}", id, solution);

            int result = feedbackMapper.updateSolution(id, solution, LocalDateTime.now());
            if (result > 0) {
                logger.info("Feedback solution updated successfully, ID: {}", id);
                return true;
            } else {
                logger.error("Failed to update feedback solution, ID: {}", id);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error occurred while updating feedback solution, ID: {}", id, e);
            throw new RuntimeException("Failed to update feedback solution: " + e.getMessage());
        }
    }

    /**
     * Delete feedback record
     */
    @Transactional
    public boolean deleteFeedback(Long id) {
        try {
            logger.info("Deleting feedback record, ID: {}", id);

            int result = feedbackMapper.deleteById(id);
            if (result > 0) {
                logger.info("Feedback record deleted successfully, ID: {}", id);
                return true;
            } else {
                logger.error("Failed to delete feedback record, ID: {}", id);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error occurred while deleting feedback record, ID: {}", id, e);
            throw new RuntimeException("Failed to delete feedback record: " + e.getMessage());
        }
    }

    /**
     * Query all feedback records
     */
    public List<Feedback> getAllFeedbacks() {
        try {
            logger.info("Querying all feedback records");
            return feedbackMapper.selectAll();
        } catch (Exception e) {
            logger.error("Error occurred while querying all feedback records", e);
            throw new RuntimeException("Failed to query all feedback records: " + e.getMessage());
        }
    }

    /**
     * Count user feedback
     */
    public int countFeedbacksByUserId(Long userId) {
        try {
            logger.info("Counting user feedback, user ID: {}", userId);
            return feedbackMapper.countByUserId(userId);
        } catch (Exception e) {
            logger.error("Error occurred while counting user feedback, user ID: {}", userId, e);
            throw new RuntimeException("Failed to count user feedback: " + e.getMessage());
        }
    }

    /**
     * Count feedback by type
     */
    public int countFeedbacksByType(Integer feedbackType) {
        try {
            logger.info("Counting feedback by type, type: {}", feedbackType);
            return feedbackMapper.countByFeedbackType(feedbackType);
        } catch (Exception e) {
            logger.error(
                    "Error occurred while counting feedback by type, type: {}", feedbackType, e);
            throw new RuntimeException("Failed to count feedback by type: " + e.getMessage());
        }
    }
}
