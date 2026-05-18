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

package io.agentscope.examples.bobatea.supervisor.mapper;

import io.agentscope.examples.bobatea.supervisor.entity.Feedback;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeedbackMapper {

    /**
     * Query feedback data by time range
     */
    @Select("SELECT * FROM feedback WHERE created_at BETWEEN #{startTime} AND #{endTime}")
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
    List<Feedback> selectByTimeRange(
            @Param("startTime") Date startTime, @Param("endTime") Date endTime);

    /**
     * Get the maximum month of created_at in the feedback table
     */
    @Select("SELECT DATE_FORMAT(MAX(created_at), '%Y-%m') FROM feedback")
    String selectMaxCreatedMonth();
}
