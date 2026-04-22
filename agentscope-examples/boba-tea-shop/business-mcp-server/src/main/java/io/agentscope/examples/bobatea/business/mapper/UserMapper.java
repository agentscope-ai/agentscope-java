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

import io.agentscope.examples.bobatea.business.entity.User;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * User Data Access Interface
 */
@Mapper
public interface UserMapper {

    /**
     * Query user by ID
     */
    @Select("SELECT * FROM users WHERE id = #{id}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "phone", column = "phone"),
        @Result(property = "email", column = "email"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    User selectById(@Param("id") Long id);

    /**
     * Query user by username
     */
    @Select("SELECT * FROM users WHERE username = #{username}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "phone", column = "phone"),
        @Result(property = "email", column = "email"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    User selectByUsername(@Param("username") String username);

    /**
     * Query user by phone number
     */
    @Select("SELECT * FROM users WHERE phone = #{phone}")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "phone", column = "phone"),
        @Result(property = "email", column = "email"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    User selectByPhone(@Param("phone") String phone);

    /**
     * Insert user
     */
    @Insert(
            "INSERT INTO users (id, username, phone, email, nickname, status, created_at,"
                    + " updated_at) VALUES (#{id}, #{username}, #{phone}, #{email}, #{nickname},"
                    + " #{status}, #{createdAt}, #{updatedAt})")
    int insert(User user);

    /**
     * Update user information
     */
    @Update(
            "UPDATE users SET username = #{username}, phone = #{phone}, email = #{email}, nickname"
                + " = #{nickname}, status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(User user);

    /**
     * Delete user
     */
    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * Query all users
     */
    @Select("SELECT * FROM users ORDER BY created_at DESC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "username", column = "username"),
        @Result(property = "phone", column = "phone"),
        @Result(property = "email", column = "email"),
        @Result(property = "nickname", column = "nickname"),
        @Result(property = "status", column = "status"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<User> selectAll();

    /**
     * Check if user exists
     */
    @Select("SELECT COUNT(*) FROM users WHERE id = #{id}")
    int existsById(@Param("id") Long id);

    /**
     * Check if username exists
     */
    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    int existsByUsername(@Param("username") String username);

    /**
     * Check if phone number exists
     */
    @Select("SELECT COUNT(*) FROM users WHERE phone = #{phone}")
    int existsByPhone(@Param("phone") String phone);
}
