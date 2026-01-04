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

import io.agentscope.examples.bobatea.business.entity.Product;
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
 * Product Data Access Layer - MyBatis Mapper
 */
@Mapper
public interface ProductMapper {

    /**
     * Insert product
     */
    @Insert(
            "INSERT INTO products (name, description, price, stock, shelf_time, preparation_time,"
                + " is_seasonal, season_start, season_end, is_regional, available_regions, status,"
                + " created_at, updated_at) VALUES (#{name}, #{description}, #{price}, #{stock},"
                + " #{shelfTime}, #{preparationTime}, #{isSeasonal}, #{seasonStart}, #{seasonEnd},"
                + " #{isRegional}, #{availableRegions}, #{status}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Product product);

    /**
     * Update product by ID
     */
    @Update("UPDATE products SET stock = #{stock}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateById(Product product);

    /**
     * Delete product by ID
     */
    @Delete("DELETE FROM products WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * Find product by ID
     */
    @Select("SELECT * FROM products WHERE id = #{id}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Product selectById(Long id);

    /**
     * Find product by product name
     */
    @Select("SELECT * FROM products WHERE name = #{name}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Product selectByName(String name);

    /**
     * Find product by product name and status
     */
    @Select("SELECT * FROM products WHERE name = #{name} AND status = #{status}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Product selectByNameAndStatus(@Param("name") String name, @Param("status") Integer status);

    /**
     * Find all available products
     */
    @Select("SELECT * FROM products WHERE status = 1 ORDER BY name")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Product> selectByStatusTrueOrderByName();

    /**
     * Find all available products with stock greater than 0
     */
    @Select("SELECT * FROM products WHERE status = 1 AND stock > #{stock} ORDER BY name")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Product> selectByStatusTrueAndStockGreaterThanOrderByName(Integer stock);

    /**
     * Check if product exists and is available
     */
    @Select("SELECT COUNT(*) > 0 FROM products WHERE name = #{name} AND status = 1")
    boolean existsByNameAndStatusTrue(String name);

    /**
     * Check if product stock is sufficient
     */
    @Select(
            "SELECT CASE WHEN stock >= #{quantity} THEN true ELSE false END FROM products WHERE"
                    + " name = #{name} AND status = 1")
    boolean checkStockAvailability(@Param("name") String name, @Param("quantity") Integer quantity);

    /**
     * Find seasonal products
     */
    @Select("SELECT * FROM products WHERE is_seasonal = 1 AND status = 1 ORDER BY name")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Product> selectByIsSeasonalTrueAndStatusTrueOrderByName();

    /**
     * Find regional limited products
     */
    @Select("SELECT * FROM products WHERE is_regional = true AND status = true ORDER BY name")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Product> selectByIsRegionalTrueAndStatusTrueOrderByName();

    /**
     * Fuzzy search products by product name
     */
    @Select(
            "SELECT * FROM products WHERE name LIKE CONCAT('%', #{name}, '%') AND status = 1 ORDER"
                    + " BY name")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "price", column = "price"),
        @Result(property = "stock", column = "stock"),
        @Result(property = "shelfTime", column = "shelf_time"),
        @Result(property = "preparationTime", column = "preparation_time"),
        @Result(property = "isSeasonal", column = "is_seasonal"),
        @Result(property = "seasonStart", column = "season_start"),
        @Result(property = "seasonEnd", column = "season_end"),
        @Result(property = "isRegional", column = "is_regional"),
        @Result(property = "availableRegions", column = "available_regions"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Product> selectByNameContainingIgnoreCaseAndStatusTrueOrderByName(String name);
}
