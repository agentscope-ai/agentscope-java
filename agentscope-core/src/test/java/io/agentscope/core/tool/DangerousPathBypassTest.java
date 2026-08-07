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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Verifies that dangerous-path detection cannot be bypassed via .env variants,
 * relative paths, case variations, or symlinks. Aligned with Python PR #1656.
 */
class DangerousPathBypassTest {

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private static class ProbeToolBase extends ToolBase {
        ProbeToolBase() {
            super(
                    ToolBase.builder()
                            .name("probe")
                            .description("probe tool")
                            .inputSchema(emptySchema())
                            .readOnly(true)
                            .concurrencySafe(true));
        }

        boolean check(String path) {
            return isDangerousPath(path);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("probe"));
        }
    }

    private static final ProbeToolBase PROBE = new ProbeToolBase();

    @Test
    void dotEnvIsDetected() {
        assertTrue(PROBE.check("/home/user/.env"));
    }

    @Test
    void dotEnvLocalIsDetected() {
        assertTrue(PROBE.check("/home/user/.env.local"));
    }

    @Test
    void dotEnvProductionIsDetected() {
        assertTrue(PROBE.check("/home/user/.env.production"));
    }

    @Test
    void dotEnvDevelopmentLocalIsDetected() {
        assertTrue(PROBE.check("/home/user/.env.development.local"));
    }

    @Test
    void dotEnvrcIsDetected() {
        assertTrue(PROBE.check("/home/user/.envrc"));
    }

    @Test
    void relativeDotEnvIsDetected() {
        assertTrue(PROBE.check("./.env"));
    }

    @Test
    void caseInsensitiveDotEnvIsDetected() {
        assertTrue(PROBE.check("/home/user/.ENV"));
    }

    @Test
    void symlinkToSshIsDetected(@TempDir Path tempDir) throws IOException {
        Path sshDir = tempDir.resolve(".ssh");
        Files.createDirectory(sshDir);
        Path sshConfig = sshDir.resolve("config");
        Files.writeString(sshConfig, "Host *\n");

        Path link = tempDir.resolve("innocent-link");
        createSymlinkOrSkip(link, sshConfig);

        assertTrue(PROBE.check(link.toString()));
    }

    @Test
    void symlinkToDotEnvIsDetected(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "SECRET=value\n");

        Path link = tempDir.resolve("config.txt");
        createSymlinkOrSkip(link, envFile);

        assertTrue(PROBE.check(link.toString()));
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | FileSystemException e) {
            Assumptions.abort("Symbolic links are not permitted in this environment: " + e);
        }
    }
}
