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
package io.agentscope.builder.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.builder.web.api.AdminUserController.CreateUserRequest;
import io.agentscope.builder.web.api.AdminUserController.CreateUserResponse;
import io.agentscope.builder.web.api.AdminUserController.PasswordResetRequest;
import io.agentscope.builder.web.api.AdminUserController.RolesRequest;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class AdminUserControllerTest {

    @TempDir Path tmp;

    private UserStore userStore;
    private UserAgentDefinitionStore agentStore;
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        userStore = new UserStore(tmp.resolve("users.json"));
        agentStore = new UserAgentDefinitionStore(tmp);
        controller = new AdminUserController(userStore, agentStore);
    }

    @Test
    void nonAdminCallerIsRejected() {
        Authentication user = principal("alice", "ROLE_USER");
        assertThatThrownBy(() -> controller.list(user).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Admin role required");
    }

    @Test
    void adminCanListUsers() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        // Default admin user is seeded on first load of UserStore.
        assertThat(controller.list(admin).block()).hasSize(1);
    }

    @Test
    void adminCanCreateUserAndReceivesGeneratedPassword() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        CreateUserResponse created =
                controller
                        .create(new CreateUserRequest("alice", null, List.of("user")), admin)
                        .block();
        assertThat(created).isNotNull();
        assertThat(created.user().username()).isEqualTo("alice");
        assertThat(created.generatedPassword()).isNotBlank();
        // The generated password is a real, usable password.
        var record = userStore.findByUsername("alice").orElseThrow();
        assertThat(userStore.verifyPassword(record, created.generatedPassword())).isTrue();
    }

    @Test
    void suppliedPasswordIsNotEchoedBack() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        CreateUserResponse created =
                controller
                        .create(new CreateUserRequest("bob", "MyOwnPass1", List.of("user")), admin)
                        .block();
        assertThat(created).isNotNull();
        assertThat(created.generatedPassword()).isNull();
    }

    @Test
    void duplicateUsernameReturnsConflict() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        controller.create(new CreateUserRequest("alice", "pw", List.of("user")), admin).block();
        assertThatThrownBy(
                        () ->
                                controller
                                        .create(
                                                new CreateUserRequest(
                                                        "alice", "pw2", List.of("user")),
                                                admin)
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void cannotDeleteLastAdmin() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        // The seeded 'admin' user is the only admin. Deleting must hit the last-admin guard.
        // (We use a different actor principal to bypass the self-delete guard and surface the
        // last-admin check.)
        Authentication otherAdmin = principal("other-admin", "ROLE_ADMIN");
        assertThatThrownBy(() -> controller.delete("admin", otherAdmin).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("last admin");
    }

    @Test
    void cannotDeleteSelf() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        assertThatThrownBy(() -> controller.delete("admin", admin).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot delete yourself");
    }

    @Test
    void resetPasswordChangesTheStoredHash() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        var created =
                controller
                        .create(new CreateUserRequest("alice", "old-pass", List.of("user")), admin)
                        .block();
        assertThat(created).isNotNull();
        controller
                .resetPassword(created.user().userId(), new PasswordResetRequest("new-pass"), admin)
                .block();
        var record = userStore.findById(created.user().userId()).orElseThrow();
        assertThat(userStore.verifyPassword(record, "new-pass")).isTrue();
        assertThat(userStore.verifyPassword(record, "old-pass")).isFalse();
    }

    @Test
    void updateRolesReplacesRoleList() {
        Authentication admin = principal("admin", "ROLE_ADMIN");
        var created =
                controller
                        .create(new CreateUserRequest("alice", "pw", List.of("user")), admin)
                        .block();
        assertThat(created).isNotNull();
        var updated =
                controller
                        .updateRoles(
                                created.user().userId(),
                                new RolesRequest(List.of("user", "admin")),
                                admin)
                        .block();
        assertThat(updated).isNotNull();
        assertThat(updated.roles()).containsExactly("user", "admin");
    }

    private static Authentication principal(String userId, String role) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority(role)));
    }
}
