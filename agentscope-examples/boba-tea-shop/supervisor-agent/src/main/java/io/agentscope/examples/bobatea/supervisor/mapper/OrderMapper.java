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
 * 订单数据访问层 - MyBatis Mapper
 */
@Mapper
public interface OrderMapper {

    /**
     * 获取订单列表中created_at的最大月份
     */
    @Select("SELECT DATE_FORMAT(MAX(created_at), '%Y-%m') FROM feedback")
    String selectMaxCreatedMonth();

    /**
     * 根据时间范围查询订单列表
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 订单列表
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
     * 根据用户ID和时间范围查询订单列表
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 订单列表
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
     * 统计时间范围内的订单数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 订单数量
     */
    @Select("SELECT COUNT(*) FROM orders WHERE created_at BETWEEN #{startTime} AND #{endTime}")
    int countOrdersByTimeRange(@Param("startTime") Date startTime, @Param("endTime") Date endTime);

    /**
     * 统计用户在时间范围内的订单数量
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 订单数量
     */
    @Select(
            "SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND created_at BETWEEN"
                    + " #{startTime} AND #{endTime}")
    int countOrdersByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);
}
