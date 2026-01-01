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

package io.agentscope.examples.bobatea.business.mapper;

import io.agentscope.examples.bobatea.business.entity.Order;
import java.time.LocalDateTime;
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
 * Order Data Access Layer - MyBatis Mapper
 */
@Mapper
public interface OrderMapper {

    /**
     * Insert order
     */
    @Insert(
            "INSERT INTO orders (order_id, user_id, product_id, product_name, sweetness, ice_level,"
                + " quantity, unit_price, total_price, remark, created_at, updated_at) VALUES"
                + " (#{orderId}, #{userId}, #{productId}, #{productName}, #{sweetness},"
                + " #{iceLevel}, #{quantity}, #{unitPrice}, #{totalPrice}, #{remark}, #{createdAt},"
                + " #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    /**
     * Update order by ID
     */
    @Update("UPDATE orders SET remark = #{remark}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateById(Order order);

    /**
     * Delete order by ID
     */
    @Delete("DELETE FROM orders WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * Delete order by user ID and order ID
     */
    @Delete("DELETE FROM orders WHERE user_id = #{userId} AND order_id = #{orderId}")
    int deleteByUserIdAndOrderId(@Param("userId") Long userId, @Param("orderId") String orderId);

    /**
     * Find order by ID
     */
    @Select("SELECT * FROM orders WHERE id = #{id}")
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
    Order selectById(Long id);

    /**
     * Find order by order ID
     */
    @Select("SELECT * FROM orders WHERE order_id = #{orderId}")
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
    Order selectByOrderId(String orderId);

    /**
     * Find order by user ID and order ID
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} AND order_id = #{orderId}")
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
    Order selectByUserIdAndOrderId(@Param("userId") Long userId, @Param("orderId") String orderId);

    /**
     * Find all orders
     */
    @Select("SELECT * FROM orders ORDER BY created_at DESC")
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
    List<Order> selectAll();

    /**
     * Find all orders by user ID
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} ORDER BY created_at DESC")
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
    List<Order> selectByUserId(Long userId);

    /**
     * Find orders by user ID with pagination
     */
    @Select(
            "SELECT * FROM orders WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT"
                    + " #{offset}, #{size}")
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
    List<Order> selectByUserIdWithPagination(
            @Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    /**
     * Count user orders
     */
    @Select("SELECT COUNT(*) FROM orders WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /**
     * Count user orders within time range
     */
    @Select(
            "SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND created_at BETWEEN"
                    + " #{startTime} AND #{endTime}")
    long countByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Check if order ID exists
     */
    @Select("SELECT COUNT(*) > 0 FROM orders WHERE order_id = #{orderId}")
    boolean existsByOrderId(String orderId);

    /**
     * Find orders by user ID and multiple conditions
     */
    @Select(
            "<script>"
                    + "SELECT * FROM orders WHERE user_id = #{userId} "
                    + "<if test='productName != null and productName != \"\"'>"
                    + "AND product_name LIKE CONCAT('%', #{productName}, '%') "
                    + "</if>"
                    + "<if test='sweetness != null'>"
                    + "AND sweetness = #{sweetness} "
                    + "</if>"
                    + "<if test='iceLevel != null'>"
                    + "AND ice_level = #{iceLevel} "
                    + "</if>"
                    + "<if test='startTime != null'>"
                    + "AND created_at &gt;= #{startTime} "
                    + "</if>"
                    + "<if test='endTime != null'>"
                    + "AND created_at &lt;= #{endTime} "
                    + "</if>"
                    + "ORDER BY created_at DESC"
                    + "</script>")
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
    List<Order> selectByUserIdAndConditions(
            @Param("userId") Long userId,
            @Param("productName") String productName,
            @Param("sweetness") Integer sweetness,
            @Param("iceLevel") Integer iceLevel,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}
