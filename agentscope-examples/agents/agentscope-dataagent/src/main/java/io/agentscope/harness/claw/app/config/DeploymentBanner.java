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
package io.agentscope.harness.claw.app.config;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints a one-shot deployment-mode banner at startup so operators can verify which subsystems are
 * single-node vs cluster-aware. Also fails loudly on combinations that are known to silently
 * corrupt state under multi-replica deployment.
 *
 * <h2>Subsystem cluster-readiness (as of Phase 4)</h2>
 *
 * <ul>
 *   <li>Sandbox state — cluster-aware when {@code claw.session.redis.enabled=true} via
 *       {@code SandboxDistributedOptions}.
 *   <li>Tool-event bus — cluster-aware via {@code RedisToolEventBus} when Redis is enabled;
 *       otherwise single-node ({@code LocalToolEventBus}).
 *   <li>User-binding store — cluster-aware via Redis KvStore when enabled.
 *   <li>Workspace timeline ({@code .workspace.jsonl}) — file append; lives on the shared workspace
 *       volume in cluster mode.
 *   <li>Other JSON stores (users.json, usage, sessions.json) — file-backed; require the workspace
 *       volume to be shared (NFS/EFS) for multi-replica visibility.
 *   <li>In-memory log SSE — single-node only.
 * </ul>
 */
@Component
public class DeploymentBanner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentBanner.class);

    /**
     * Heuristic list of filesystem prefixes that almost certainly indicate the workspace lives on
     * ephemeral local storage. Production deployments with Redis enabled MUST point {@code
     * claw.workspace} at a shared volume.
     */
    private static final List<String> EPHEMERAL_PREFIXES =
            List.of("/tmp/", "/var/tmp/", "/private/tmp/", "/dev/shm/");

    @Value("${claw.session.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${claw.sandbox.enabled:false}")
    private boolean sandboxEnabled;

    @Value("${claw.sandbox.isolation:USER}")
    private String sandboxIsolation;

    @Value("${claw.workspace:}")
    private String workspaceDir;

    private final Environment environment;

    public DeploymentBanner(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void emit() {
        String mode = redisEnabled ? "CLUSTERED" : "SINGLE-NODE";
        log.info("============================================================");
        log.info(" claw deployment mode: {}", mode);
        log.info("   sandbox             : {}", sandboxEnabled ? "ENABLED" : "disabled");
        log.info("   sandbox isolation   : {}", sandboxIsolation);
        log.info("   sandbox state       : {}", redisEnabled ? "Redis-backed" : "in-process");
        log.info(
                "   tool-event bus      : {}",
                redisEnabled ? "Redis pub/sub" : "in-process (single-replica visibility)");
        log.info(
                "   user bindings       : {}",
                redisEnabled ? "Redis-backed" : "local file (single-replica visibility)");
        log.info("   workspace timeline  : local JSONL (requires shared volume for cluster mode)");
        log.info("   other JSON stores   : local files (require shared volume for cluster mode)");
        log.info("   log SSE stream      : in-memory (single-replica visibility)");
        log.info("============================================================");

        if (redisEnabled && "SESSION".equalsIgnoreCase(sandboxIsolation) && !sandboxEnabled) {
            throw new IllegalStateException(
                    "Inconsistent configuration: claw.session.redis.enabled=true with"
                            + " claw.sandbox.isolation=SESSION but claw.sandbox.enabled=false."
                            + " Per-session isolation requires the sandbox subsystem.");
        }

        if (!redisEnabled && sandboxEnabled && "SESSION".equalsIgnoreCase(sandboxIsolation)) {
            log.warn(
                    "Sandbox isolation=SESSION without claw.session.redis.enabled=true. Sessions"
                        + " bound to a specific replica will be inaccessible from other replicas."
                        + " Enable Redis for horizontal scale.");
        }

        if (redisEnabled) {
            checkWorkspaceIsShared();
        }
    }

    private void checkWorkspaceIsShared() {
        // dev profile is a single-node convenience setting; skip the check there.
        boolean devProfile = false;
        for (String p : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(p)) {
                devProfile = true;
                break;
            }
        }
        if (devProfile) return;

        if (workspaceDir == null || workspaceDir.isBlank()) {
            // Already enforced by ClawAppConfig in non-dev profiles, but be explicit.
            throw new IllegalStateException(
                    "claw.session.redis.enabled=true requires an explicit claw.workspace pointing"
                            + " at a shared volume (NFS/EFS). Got: empty.");
        }
        Path ws = Path.of(workspaceDir).toAbsolutePath().normalize();
        String wsStr = ws.toString();
        for (String prefix : EPHEMERAL_PREFIXES) {
            if (wsStr.startsWith(prefix) || (wsStr + "/").startsWith(prefix)) {
                throw new IllegalStateException(
                        "claw.session.redis.enabled=true but claw.workspace='"
                                + wsStr
                                + "' looks like ephemeral local storage. In cluster mode, every"
                                + " replica must see the same workspace files (skills, sessions,"
                                + " user JSON stores). Point claw.workspace at a shared volume"
                                + " (NFS/EFS) or unset claw.session.redis.enabled.");
            }
        }
    }
}
