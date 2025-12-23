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
 * 用户数据访问接口
 */
@Mapper
public interface UserMapper {

    /**
     * 根据ID查询用户
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
     * 根据用户名查询用户
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
     * 根据手机号查询用户
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
     * 插入用户
     */
    @Insert(
            "INSERT INTO users (id, username, phone, email, nickname, status, created_at,"
                    + " updated_at) VALUES (#{id}, #{username}, #{phone}, #{email}, #{nickname},"
                    + " #{status}, #{createdAt}, #{updatedAt})")
    int insert(User user);

    /**
     * 更新用户信息
     */
    @Update(
            "UPDATE users SET username = #{username}, phone = #{phone}, email = #{email}, nickname"
                + " = #{nickname}, status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(User user);

    /**
     * 删除用户
     */
    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 查询所有用户
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
     * 检查用户是否存在
     */
    @Select("SELECT COUNT(*) FROM users WHERE id = #{id}")
    int existsById(@Param("id") Long id);

    /**
     * 检查用户名是否存在
     */
    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    int existsByUsername(@Param("username") String username);

    /**
     * 检查手机号是否存在
     */
    @Select("SELECT COUNT(*) FROM users WHERE phone = #{phone}")
    int existsByPhone(@Param("phone") String phone);
}
