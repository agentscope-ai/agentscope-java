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

package io.agentscope.examples.bobatea.business.entity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

/**
 * User Entity Class
 */
public class User {

    @NotNull(message = "User ID cannot be empty")
    private Long id;

    @NotBlank(message = "Username cannot be empty")
    private String username;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "Phone number format is incorrect")
    private String phone;

    @Email(message = "Email format is incorrect")
    private String email;

    private String nickname;

    private Integer status = 1; // Status: 0-disabled, 1-active

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public User() {}

    public User(Long id, String username, String phone, String email, String nickname) {
        this.id = id;
        this.username = username;
        this.phone = phone;
        this.email = email;
        this.nickname = nickname;
        this.status = 1;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "User{"
                + "id="
                + id
                + ", username='"
                + username
                + '\''
                + ", phone='"
                + phone
                + '\''
                + ", email='"
                + email
                + '\''
                + ", nickname='"
                + nickname
                + '\''
                + ", status="
                + status
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
