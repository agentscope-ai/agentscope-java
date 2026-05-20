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
package io.agentscope.harness.claw.web.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the user registry written by agentscope-claw.
 *
 * <p>Reads {@code .agentscope/users.json} from the shared workspace. The admin console does not
 * write to this file; all user-management writes happen via agentscope-claw's
 * {@code /api/admin/users} endpoints.
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<UserRecord>> LIST_TYPE = new TypeReference<>() {};

    private final Path usersFile;

    public UserStore(@Value("${claw-web.workspace:}") String workspaceDir) {
        Path cwd =
                workspaceDir != null && !workspaceDir.isBlank()
                        ? Path.of(workspaceDir)
                        : Path.of(System.getProperty("user.dir"));
        this.usersFile = cwd.resolve(".agentscope").resolve("users.json").normalize();
    }

    public List<UserRecord> listAll() {
        if (!Files.exists(usersFile)) return List.of();
        try {
            return MAPPER.readValue(usersFile.toFile(), LIST_TYPE);
        } catch (IOException e) {
            log.warn("Failed to read users.json: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<UserRecord> findById(String userId) {
        return listAll().stream().filter(u -> userId.equals(u.userId())).findFirst();
    }

    /** Minimal user record (password hash intentionally excluded from this read-only view). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRecord(String userId, String username, List<String> roles) {}
}
