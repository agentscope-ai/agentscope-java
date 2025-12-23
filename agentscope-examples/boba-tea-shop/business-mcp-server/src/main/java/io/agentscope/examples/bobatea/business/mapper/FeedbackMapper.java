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

package io.agentscope.examples.bobatea.business.mapper;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 反馈数据访问层
 */
@Mapper
public interface FeedbackMapper {

    /**
     * 插入反馈记录
     */
    @Insert(
            "INSERT INTO feedback (order_id, user_id, feedback_type, rating, content, solution,"
                    + " created_at, updated_at) VALUES (#{orderId}, #{userId}, #{feedbackType},"
                    + " #{rating}, #{content}, #{solution}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Feedback feedback);

    /**
     * 根据ID查询反馈记录
     */
    @Select("SELECT * FROM feedback WHERE id = #{id}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "feedbackType", column = "feedback_type"),
        @Result(property = "rating", column = "rating"),
        @Result(property = "content", column = "content"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Feedback selectById(Long id);

    /**
     * 根据用户ID查询反馈记录
     */
    @Select("SELECT * FROM feedback WHERE user_id = #{userId} ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "feedbackType", column = "feedback_type"),
        @Result(property = "rating", column = "rating"),
        @Result(property = "content", column = "content"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Feedback> selectByUserId(Long userId);

    /**
     * 根据订单ID查询反馈记录
     */
    @Select("SELECT * FROM feedback WHERE order_id = #{orderId} ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "feedbackType", column = "feedback_type"),
        @Result(property = "rating", column = "rating"),
        @Result(property = "content", column = "content"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Feedback> selectByOrderId(String orderId);

    /**
     * 根据反馈类型查询反馈记录
     */
    @Select("SELECT * FROM feedback WHERE feedback_type = #{feedbackType} ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "feedbackType", column = "feedback_type"),
        @Result(property = "rating", column = "rating"),
        @Result(property = "content", column = "content"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Feedback> selectByFeedbackType(Integer feedbackType);

    /**
     * 更新反馈记录
     */
    @Update(
            "UPDATE feedback SET "
                    + "order_id = #{orderId}, "
                    + "user_id = #{userId}, "
                    + "feedback_type = #{feedbackType}, "
                    + "rating = #{rating}, "
                    + "content = #{content}, "
                    + "solution = #{solution}, "
                    + "updated_at = #{updatedAt} "
                    + "WHERE id = #{id}")
    int update(Feedback feedback);

    /**
     * 更新反馈解决方案
     */
    @Update(
            "UPDATE feedback SET solution = #{solution}, updated_at = #{updatedAt} WHERE id ="
                    + " #{id}")
    int updateSolution(
            @Param("id") Long id,
            @Param("solution") String solution,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * 根据ID删除反馈记录
     */
    @Delete("DELETE FROM feedback WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * 查询所有反馈记录
     */
    @Select("SELECT * FROM feedback ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "feedbackType", column = "feedback_type"),
        @Result(property = "rating", column = "rating"),
        @Result(property = "content", column = "content"),
        @Result(property = "solution", column = "solution"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Feedback> selectAll();

    /**
     * 统计用户反馈数量
     */
    @Select("SELECT COUNT(*) FROM feedback WHERE user_id = #{userId}")
    int countByUserId(Long userId);

    /**
     * 统计反馈类型数量
     */
    @Select("SELECT COUNT(*) FROM feedback WHERE feedback_type = #{feedbackType}")
    int countByFeedbackType(Integer feedbackType);
}
