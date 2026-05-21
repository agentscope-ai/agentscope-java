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
package io.agentscope.harness.claw.app.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for user management.
 *
 * <ul>
 *   <li>{@code GET /api/admin/users} — admin: list all users
 *   <li>{@code POST /api/admin/users} — admin: create user
 *   <li>{@code PATCH /api/admin/users/{id}/roles} — admin: update roles
 *   <li>{@code DELETE /api/admin/users/{id}} — admin: delete user
 *   <li>{@code POST /api/user/change-password} — any authenticated user: change own password
 *   <li>{@code GET /api/user/profile} — any authenticated user: view own profile
 * </ul>
 */
@RestController
public class UserController {

    private final UserStore userStore;

    public UserController(UserStore userStore) {
        this.userStore = userStore;
    }

    // -----------------------------------------------------------------
    //  Admin endpoints
    // -----------------------------------------------------------------

    @GetMapping("/api/admin/users")
    public Mono<List<UserView>> listUsers() {
        return Mono.fromCallable(
                () ->
                        userStore.listAll().stream()
                                .map(u -> new UserView(u.userId(), u.username(), u.roles()))
                                .toList());
    }

    @PostMapping("/api/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserView> createUser(@RequestBody CreateUserRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req.username() == null || req.username().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "username is required");
                    }
                    if (req.password() == null || req.password().length() < 6) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "password must be at least 6 characters");
                    }
                    String userId =
                            req.userId() != null && !req.userId().isBlank()
                                    ? req.userId()
                                    : UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    List<String> roles =
                            req.roles() != null && !req.roles().isEmpty()
                                    ? req.roles()
                                    : List.of("user");
                    try {
                        UserStore.UserRecord u =
                                userStore.createUser(userId, req.username(), req.password(), roles);
                        return new UserView(u.userId(), u.username(), u.roles());
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                    }
                });
    }

    @PatchMapping("/api/admin/users/{id}/roles")
    public Mono<UserView> updateRoles(
            @PathVariable String id, @RequestBody UpdateRolesRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req.roles() == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roles required");
                    }
                    return userStore
                            .updateRoles(id, req.roles())
                            .map(u -> new UserView(u.userId(), u.username(), u.roles()))
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND, "User not found: " + id));
                });
    }

    @DeleteMapping("/api/admin/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteUser(@PathVariable String id) {
        return Mono.fromRunnable(
                () -> {
                    try {
                        if (!userStore.deleteUser(id)) {
                            throw new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "User not found: " + id);
                        }
                    } catch (IllegalStateException e) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                    }
                });
    }

    @PatchMapping("/api/admin/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> adminResetPassword(
            @PathVariable String id, @RequestBody ChangePasswordRequest req) {
        return Mono.fromRunnable(
                () -> {
                    if (req.newPassword() == null || req.newPassword().length() < 6) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "newPassword must be at least 6 characters");
                    }
                    if (userStore.updatePassword(id, req.newPassword()).isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "User not found: " + id);
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Self-service endpoints (any authenticated user)
    // -----------------------------------------------------------------

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

    public record CreateUserRequest(
            String userId, String username, String password, List<String> roles) {}

    public record UpdateRolesRequest(List<String> roles) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
