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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.auth.UserStore.UserRecord;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore.StoredEntry;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Admin-only user management endpoints.
 *
 * <ul>
 *   <li>{@code GET    /api/admin/users} — list all users
 *   <li>{@code POST   /api/admin/users} — create user; returns generated temp password if none
 *       supplied (admin sees it once at creation time)
 *   <li>{@code PATCH  /api/admin/users/{id}/password} — reset password
 *   <li>{@code PATCH  /api/admin/users/{id}/roles} — replace roles (last-admin guard from
 *       {@link UserStore})
 *   <li>{@code DELETE /api/admin/users/{id}} — delete user; cascade-revokes every
 *       {@code (USER, deletedId)} grant across all agents. Workspace files are NOT removed so
 *       audit trail is preserved; admin may clean them up manually.
 * </ul>
 *
 * <p>All endpoints require the {@code ADMIN} role; non-admin callers receive {@code 403}. There is
 * no self-registration endpoint — accounts only exist when an admin invites them.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserStore userStore;
    private final UserAgentDefinitionStore agentStore;

    public AdminUserController(UserStore userStore, UserAgentDefinitionStore agentStore) {
        this.userStore = userStore;
        this.agentStore = agentStore;
    }

    @GetMapping
    public Mono<List<AdminUserView>> list(Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> userStore.listAll().stream().map(AdminUserController::toView).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreateUserResponse> create(
            @RequestBody CreateUserRequest req, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.username() == null || req.username().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "username is required");
                    }
                    String username = req.username().trim();
                    List<String> roles =
                            req.roles() == null || req.roles().isEmpty()
                                    ? List.of("user")
                                    : List.copyOf(req.roles());
                    boolean generated =
                            req.initialPassword() == null || req.initialPassword().isBlank();
                    String password = generated ? generateTempPassword() : req.initialPassword();
                    String userId = makeUserId(username);
                    try {
                        UserRecord created =
                                userStore.createUser(userId, username, password, roles);
                        log.info(
                                "Admin '{}' created user '{}' (roles={})",
                                auth.getPrincipal(),
                                username,
                                roles);
                        return new CreateUserResponse(toView(created), generated ? password : null);
                    } catch (IllegalArgumentException dup) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, dup.getMessage());
                    }
                });
    }

    @PatchMapping("/{userId}/password")
    public Mono<AdminUserView> resetPassword(
            @PathVariable String userId,
            @RequestBody PasswordResetRequest req,
            Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.newPassword() == null || req.newPassword().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "newPassword is required");
                    }
                    return userStore
                            .updatePassword(userId, req.newPassword())
                            .map(AdminUserController::toView)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "User not found: " + userId));
                });
    }

    @PatchMapping("/{userId}/roles")
    public Mono<AdminUserView> updateRoles(
            @PathVariable String userId, @RequestBody RolesRequest req, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.roles() == null || req.roles().isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "roles must contain at least one entry");
                    }
                    // The last-admin guard is the responsibility of UserStore; surface its
                    // IllegalStateException as a 409 so the UI can show a friendly message.
                    try {
                        return userStore
                                .updateRoles(userId, req.roles())
                                .map(AdminUserController::toView)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "User not found: " + userId));
                    } catch (IllegalStateException ex) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
                    }
                });
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String userId, Authentication auth) {
        requireAdmin(auth);
        String actor = (String) auth.getPrincipal();
        if (userId.equals(actor)) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete yourself"));
        }
        return Mono.fromRunnable(
                () -> {
                    try {
                        if (!userStore.deleteUser(userId)) {
                            throw new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "User not found: " + userId);
                        }
                    } catch (IllegalStateException ex) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
                    }
                    // Cascade-revoke every (USER, userId) grant across every agent in every owner's
                    // store. Workspace files are not touched — preserves audit trail; admin can
                    // manually clean up afterwards.
                    revokeAllGrantsFor(userId);
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static void requireAdmin(Authentication auth) {
        if (auth == null
                || auth.getAuthorities() == null
                || auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    private void revokeAllGrantsFor(String revokedUserId) {
        for (UserRecord owner : userStore.listAll()) {
            for (StoredEntry entry : agentStore.list(owner.userId())) {
                List<AgentShareGrant> shares = entry.shares();
                if (shares == null || shares.isEmpty()) continue;
                List<AgentShareGrant> remaining = new ArrayList<>(shares.size());
                boolean changed = false;
                for (AgentShareGrant g : shares) {
                    if (AgentShareGrant.GRANTEE_USER.equals(g.granteeType())
                            && revokedUserId.equals(g.granteeId())) {
                        changed = true;
                        continue;
                    }
                    remaining.add(g);
                }
                if (changed) {
                    agentStore.save(owner.userId(), withShares(entry, remaining));
                    log.info(
                            "Revoked (USER, {}) grant on agent {}/{}",
                            revokedUserId,
                            owner.userId(),
                            entry.id());
                }
            }
        }
    }

    private static StoredEntry withShares(StoredEntry e, List<AgentShareGrant> newShares) {
        return new StoredEntry(
                e.id(),
                e.name(),
                e.description(),
                e.sysPrompt(),
                e.model(),
                e.maxIters(),
                e.toolsAllow(),
                e.toolsDeny(),
                e.identityName(),
                e.identityEmoji(),
                e.groupChatMentionPatterns(),
                e.groupChatRequireMention(),
                e.skillsAllow(),
                e.skillsDeny(),
                e.createdAt(),
                e.updatedAt(),
                newShares.isEmpty() ? null : newShares,
                e.runAs(),
                e.forkOf(),
                e.workspacePath(),
                e.skillRepositories(),
                e.sandboxMode(),
                e.sandboxScope());
    }

    private static AdminUserView toView(UserRecord u) {
        return new AdminUserView(u.userId(), u.username(), u.roles());
    }

    private static String makeUserId(String username) {
        // Stable, opaque id distinct from username so renames don't break references.
        String sanitised = username.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return sanitised + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /**
     * Generates a temporary password using a small unambiguous alphabet (no 0/O/1/l). The plaintext
     * is shown to the admin exactly once at creation time; on the wire, the response is HTTPS-only
     * (the SPA fetches over the same origin) and is never logged.
     */
    private static String generateTempPassword() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserView(String userId, String username, List<String> roles) {}

    public record CreateUserRequest(String username, String initialPassword, List<String> roles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateUserResponse(AdminUserView user, String generatedPassword) {}

    public record PasswordResetRequest(String newPassword) {}

    public record RolesRequest(List<String> roles) {}
}
