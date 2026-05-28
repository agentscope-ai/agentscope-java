/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.auth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for self-service user account operations.
 *
 * <ul>
 *   <li>{@code GET /api/user/profile} — view own profile
 *   <li>{@code POST /api/user/change-password} — change own password
 * </ul>
 */
@RestController
public class UserController {

    private final UserStore userStore;

    public UserController(UserStore userStore) {
        this.userStore = userStore;
    }

    @GetMapping("/api/user/profile")
    public Mono<UserView> profile(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        userStore
                                .findById(userId)
                                .map(u -> new UserView(u.userId(), u.username(), u.roles()))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND, "User not found")));
    }

    @PostMapping("/api/user/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> changePassword(@RequestBody ChangePasswordRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    if (req.newPassword() == null || req.newPassword().length() < 6) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "newPassword must be at least 6 characters");
                    }
                    // Verify current password if provided
                    if (req.currentPassword() != null && !req.currentPassword().isBlank()) {
                        UserStore.UserRecord user =
                                userStore
                                        .findById(userId)
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "User not found"));
                        if (!userStore.verifyPassword(user, req.currentPassword())) {
                            throw new ResponseStatusException(
                                    HttpStatus.UNAUTHORIZED, "Current password is incorrect");
                        }
                    }
                    if (userStore.updatePassword(userId, req.newPassword()).isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
                    }
                });
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record UserView(String userId, String username, List<String> roles) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
