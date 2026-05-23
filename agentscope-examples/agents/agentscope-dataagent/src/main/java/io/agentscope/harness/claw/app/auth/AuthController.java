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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for authentication endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — authenticate with username/password, returns JWT
 *   <li>{@code GET /api/auth/me} — returns current user info (requires JWT)
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserStore userStore;
    private final JwtService jwtService;

    public AuthController(UserStore userStore, JwtService jwtService) {
        this.userStore = userStore;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String userId, String username, List<String> roles) {}

    public record MeResponse(String userId, String username, List<String> roles) {}

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req.username() == null || req.password() == null) {
                        return ResponseEntity.badRequest().<LoginResponse>build();
                    }
                    return userStore
                            .findByUsername(req.username())
                            .filter(u -> userStore.verifyPassword(u, req.password()))
                            .map(
                                    u -> {
                                        String token =
                                                jwtService.generate(
                                                        u.userId(), u.username(), u.roles());
                                        return ResponseEntity.ok(
                                                new LoginResponse(
                                                        token,
                                                        u.userId(),
                                                        u.username(),
                                                        u.roles()));
                                    })
                            .orElse(
                                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                            .<LoginResponse>build());
                });
    }

    @GetMapping("/me")
    public Mono<MeResponse> me(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        userStore
                                .findById(userId)
                                .map(u -> new MeResponse(u.userId(), u.username(), u.roles()))
                                .orElse(new MeResponse(userId, userId, List.of("user"))));
    }
}
