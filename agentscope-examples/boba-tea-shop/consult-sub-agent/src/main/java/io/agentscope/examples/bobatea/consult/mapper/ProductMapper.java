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

package io.agentscope.examples.bobatea.consult.mapper;

import io.agentscope.examples.bobatea.consult.entity.Product;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * Product Data Access Interface
 */
@Mapper
public interface ProductMapper {

    /**
     * Query product by ID
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
    Product selectById(@Param("id") Long id);

    /**
     * Query product by product name
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
    Product selectByName(@Param("name") String name);

    /**
     * Query product by product name and status
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
     * Fuzzy query product list by product name
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
    List<Product> selectByNameLike(@Param("name") String name);

    /**
     * Query all available products
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
    List<Product> selectAllAvailable();

    /**
     * Query all products
     */
    @Select("SELECT * FROM products ORDER BY name")
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
    List<Product> selectAll();

    /**
     * Check if product exists and is available
     */
    @Select("SELECT COUNT(*) FROM products WHERE name = #{name} AND status = 1")
    int existsByNameAndStatusTrue(@Param("name") String name);

    /**
     * Check if product exists
     */
    @Select("SELECT COUNT(*) FROM products WHERE name = #{name}")
    int existsByName(@Param("name") String name);
}
