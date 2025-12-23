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
     * 创建反馈记录
     */
    @Transactional
    public Feedback createFeedback(Feedback feedback) {
        try {
            logger.info(
                    "创建反馈记录，用户ID: {}, 反馈类型: {}", feedback.getUserId(), feedback.getFeedbackType());

            // 设置创建时间
            feedback.onCreate();

            int result = feedbackMapper.insert(feedback);
            if (result > 0) {
                logger.info("反馈记录创建成功，ID: {}", feedback.getId());
                return feedback;
            } else {
                logger.error("反馈记录创建失败");
                throw new RuntimeException("反馈记录创建失败");
            }
        } catch (Exception e) {
            logger.error("创建反馈记录时发生错误", e);
            throw new RuntimeException("创建反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID查询反馈记录
     */
    public Optional<Feedback> getFeedbackById(Long id) {
        try {
            logger.info("查询反馈记录，ID: {}", id);
            Feedback feedback = feedbackMapper.selectById(id);
            return Optional.ofNullable(feedback);
        } catch (Exception e) {
            logger.error("查询反馈记录时发生错误，ID: {}", id, e);
            throw new RuntimeException("查询反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 根据用户ID查询反馈记录
     */
    public List<Feedback> getFeedbacksByUserId(Long userId) {
        try {
            logger.info("查询用户反馈记录，用户ID: {}", userId);
            return feedbackMapper.selectByUserId(userId);
        } catch (Exception e) {
            logger.error("查询用户反馈记录时发生错误，用户ID: {}", userId, e);
            throw new RuntimeException("查询用户反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 根据订单ID查询反馈记录
     */
    public List<Feedback> getFeedbacksByOrderId(String orderId) {
        try {
            logger.info("查询订单反馈记录，订单ID: {}", orderId);
            return feedbackMapper.selectByOrderId(orderId);
        } catch (Exception e) {
            logger.error("查询订单反馈记录时发生错误，订单ID: {}", orderId, e);
            throw new RuntimeException("查询订单反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 根据反馈类型查询反馈记录
     */
    public List<Feedback> getFeedbacksByType(Integer feedbackType) {
        try {
            logger.info("查询反馈类型记录，类型: {}", feedbackType);
            return feedbackMapper.selectByFeedbackType(feedbackType);
        } catch (Exception e) {
            logger.error("查询反馈类型记录时发生错误，类型: {}", feedbackType, e);
            throw new RuntimeException("查询反馈类型记录失败: " + e.getMessage());
        }
    }

    /**
     * 更新反馈记录
     */
    @Transactional
    public Feedback updateFeedback(Feedback feedback) {
        try {
            logger.info("更新反馈记录，ID: {}", feedback.getId());

            // 设置更新时间
            feedback.onUpdate();

            int result = feedbackMapper.update(feedback);
            if (result > 0) {
                logger.info("反馈记录更新成功，ID: {}", feedback.getId());
                return feedback;
            } else {
                logger.error("反馈记录更新失败，ID: {}", feedback.getId());
                throw new RuntimeException("反馈记录更新失败");
            }
        } catch (Exception e) {
            logger.error("更新反馈记录时发生错误，ID: {}", feedback.getId(), e);
            throw new RuntimeException("更新反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 更新反馈解决方案
     */
    @Transactional
    public boolean updateFeedbackSolution(Long id, String solution) {
        try {
            logger.info("更新反馈解决方案，ID: {}, 解决方案: {}", id, solution);

            int result = feedbackMapper.updateSolution(id, solution, LocalDateTime.now());
            if (result > 0) {
                logger.info("反馈解决方案更新成功，ID: {}", id);
                return true;
            } else {
                logger.error("反馈解决方案更新失败，ID: {}", id);
                return false;
            }
        } catch (Exception e) {
            logger.error("更新反馈解决方案时发生错误，ID: {}", id, e);
            throw new RuntimeException("更新反馈解决方案失败: " + e.getMessage());
        }
    }

    /**
     * 删除反馈记录
     */
    @Transactional
    public boolean deleteFeedback(Long id) {
        try {
            logger.info("删除反馈记录，ID: {}", id);

            int result = feedbackMapper.deleteById(id);
            if (result > 0) {
                logger.info("反馈记录删除成功，ID: {}", id);
                return true;
            } else {
                logger.error("反馈记录删除失败，ID: {}", id);
                return false;
            }
        } catch (Exception e) {
            logger.error("删除反馈记录时发生错误，ID: {}", id, e);
            throw new RuntimeException("删除反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 查询所有反馈记录
     */
    public List<Feedback> getAllFeedbacks() {
        try {
            logger.info("查询所有反馈记录");
            return feedbackMapper.selectAll();
        } catch (Exception e) {
            logger.error("查询所有反馈记录时发生错误", e);
            throw new RuntimeException("查询所有反馈记录失败: " + e.getMessage());
        }
    }

    /**
     * 统计用户反馈数量
     */
    public int countFeedbacksByUserId(Long userId) {
        try {
            logger.info("统计用户反馈数量，用户ID: {}", userId);
            return feedbackMapper.countByUserId(userId);
        } catch (Exception e) {
            logger.error("统计用户反馈数量时发生错误，用户ID: {}", userId, e);
            throw new RuntimeException("统计用户反馈数量失败: " + e.getMessage());
        }
    }

    /**
     * 统计反馈类型数量
     */
    public int countFeedbacksByType(Integer feedbackType) {
        try {
            logger.info("统计反馈类型数量，类型: {}", feedbackType);
            return feedbackMapper.countByFeedbackType(feedbackType);
        } catch (Exception e) {
            logger.error("统计反馈类型数量时发生错误，类型: {}", feedbackType, e);
            throw new RuntimeException("统计反馈类型数量失败: " + e.getMessage());
        }
    }
}
