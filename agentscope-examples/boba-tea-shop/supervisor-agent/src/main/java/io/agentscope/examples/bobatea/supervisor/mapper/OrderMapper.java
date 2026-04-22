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

import io.agentscope.examples.bobatea.supervisor.entity.Order;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * Order Data Access Layer - MyBatis Mapper
 */
@Mapper
public interface OrderMapper {

    /**
     * Get the maximum month of created_at in the order list
     */
    @Select("SELECT DATE_FORMAT(MAX(created_at), '%Y-%m') FROM orders")
    String selectMaxCreatedMonth();

    /**
     * Query order list by time range
     *
     * @param startTime Start time
     * @param endTime   End time
     * @return Order list
     */
    @Select(
            "SELECT * FROM orders WHERE created_at BETWEEN #{startTime} AND #{endTime} ORDER BY"
                    + " created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "productId", column = "product_id"),
        @Result(property = "productName", column = "product_name"),
        @Result(property = "sweetness", column = "sweetness"),
        @Result(property = "iceLevel", column = "ice_level"),
        @Result(property = "quantity", column = "quantity"),
        @Result(property = "unitPrice", column = "unit_price"),
        @Result(property = "totalPrice", column = "total_price"),
        @Result(property = "remark", column = "remark"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Order> findOrdersByTimeRange(
            @Param("startTime") Date startTime, @Param("endTime") Date endTime);

    /**
     * Query order list by user ID and time range
     *
     * @param userId    User ID
     * @param startTime Start time
     * @param endTime   End time
     * @return Order list
     */
    @Select(
            "SELECT * FROM orders WHERE user_id = #{userId} AND created_at BETWEEN #{startTime} AND"
                    + " #{endTime} ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "productId", column = "product_id"),
        @Result(property = "productName", column = "product_name"),
        @Result(property = "sweetness", column = "sweetness"),
        @Result(property = "iceLevel", column = "ice_level"),
        @Result(property = "quantity", column = "quantity"),
        @Result(property = "unitPrice", column = "unit_price"),
        @Result(property = "totalPrice", column = "total_price"),
        @Result(property = "remark", column = "remark"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Order> findOrdersByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);

    /**
     * Count orders within time range
     *
     * @param startTime Start time
     * @param endTime   End time
     * @return Order count
     */
    @Select("SELECT COUNT(*) FROM orders WHERE created_at BETWEEN #{startTime} AND #{endTime}")
    int countOrdersByTimeRange(@Param("startTime") Date startTime, @Param("endTime") Date endTime);

    /**
     * Count user orders within time range
     *
     * @param userId    User ID
     * @param startTime Start time
     * @param endTime   End time
     * @return Order count
     */
    @Select(
            "SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND created_at BETWEEN"
                    + " #{startTime} AND #{endTime}")
    int countOrdersByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);
}
