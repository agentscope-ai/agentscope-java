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
 * 订单数据访问层 - MyBatis Mapper
 */
@Mapper
public interface OrderMapper {

    /**
     * 插入订单
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
     * 根据ID更新订单
     */
    @Update("UPDATE orders SET remark = #{remark}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateById(Order order);

    /**
     * 根据ID删除订单
     */
    @Delete("DELETE FROM orders WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * 根据用户ID和订单编号删除订单
     */
    @Delete("DELETE FROM orders WHERE user_id = #{userId} AND order_id = #{orderId}")
    int deleteByUserIdAndOrderId(@Param("userId") Long userId, @Param("orderId") String orderId);

    /**
     * 根据ID查找订单
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
     * 根据订单编号查找订单
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
     * 根据用户ID和订单编号查找订单
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
     * 查找所有订单
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
     * 根据用户ID查找所有订单
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
     * 根据用户ID分页查找订单
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
     * 统计用户订单数量
     */
    @Select("SELECT COUNT(*) FROM orders WHERE user_id = #{userId}")
    long countByUserId(Long userId);

    /**
     * 统计用户指定时间范围内的订单数量
     */
    @Select(
            "SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND created_at BETWEEN"
                    + " #{startTime} AND #{endTime}")
    long countByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 检查订单编号是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM orders WHERE order_id = #{orderId}")
    boolean existsByOrderId(String orderId);

    /**
     * 根据用户ID和多个条件查找订单
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
