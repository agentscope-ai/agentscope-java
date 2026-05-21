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
package io.agentscope.harness.claw.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Read-only view of the shared {@code .agentscope/agentscope.json} configuration file.
 *
 * <ul>
 *   <li>{@code GET /api/admin/config/agentscope} — read the raw JSON configuration
 * </ul>
 *
 * <p>Writing is not supported via the admin console. To change the configuration, edit
 * {@code agentscope.json} directly and restart agentscope-claw.
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/config")
public class ConfigController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configFile;

    public ConfigController(@Value("${claw-web.workspace:}") String workspaceDir) {
        Path cwd =
                workspaceDir != null && !workspaceDir.isBlank()
                        ? Path.of(workspaceDir)
                        : Path.of(System.getProperty("user.dir"));
        this.configFile = cwd.resolve(".agentscope").resolve("agentscope.json").normalize();
    }

    @GetMapping("/agentscope")
    public Mono<JsonNode> agentscopeConfig() {
        return Mono.fromCallable(
                () -> {
                    if (!Files.exists(configFile)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "agentscope.json not found at " + configFile);
                    }
                    return MAPPER.readTree(configFile.toFile());
                });
    }
}
