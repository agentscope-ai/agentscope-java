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
package io.agentscope.builder.control;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optionally self-registers this Java data plane with aistiod and heartbeats so the control plane
 * can poll {@code /agentscope/*} without Kubernetes discovery.
 *
 * <p>Disabled by default: the dataplane hosts Managed agent runs and should not appear as an Operate
 * agent. Enable with {@code builder.dataplane.register-enabled=true} / {@code
 * BUILDER_DATAPLANE_REGISTER=true} when that Operate visibility is intentionally desired.
 */
@Component
@ConditionalOnProperty(
        prefix = "builder.dataplane",
        name = "register-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class DataPlaneSelfRegistration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataPlaneSelfRegistration.class);

    private static final List<String> CAPABILITIES =
            List.of("session-reporting", "context-query", "message-query", "session-command");

    private final ControlPlaneClient controlPlaneClient;
    private final String agentName;
    private final String namespace;
    private final String configuredInstanceId;
    private final String publicUrl;
    private final int serverPort;
    private final String internalToken;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    private volatile String instanceId;
    private volatile String baseUrl;

    public DataPlaneSelfRegistration(
            ControlPlaneClient controlPlaneClient,
            @Value("${builder.dataplane.agent-name:agentscope-java-dataplane}") String agentName,
            @Value("${builder.dataplane.namespace:default}") String namespace,
            @Value("${builder.instance-id:}") String configuredInstanceId,
            @Value("${builder.dataplane.public-url:${BUILDER_DATAPLANE_PUBLIC_URL:}}")
                    String publicUrl,
            @Value("${server.port:8082}") int serverPort,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        this.controlPlaneClient = controlPlaneClient;
        this.agentName = agentName;
        this.namespace = namespace == null || namespace.isBlank() ? "default" : namespace;
        this.configuredInstanceId = configuredInstanceId;
        this.publicUrl = publicUrl;
        this.serverPort = serverPort;
        this.internalToken = internalToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (internalToken == null || internalToken.isBlank()) {
            log.warn(
                    "Skipping data-plane self-registration: builder.internal-token /"
                            + " BUILDER_INTERNAL_TOKEN is empty");
            return;
        }
        this.instanceId = resolveInstanceId();
        this.baseUrl = resolveBaseUrl();
        tryRegister();
    }

    /** Periodic heartbeat (and re-register if the control plane forgot us). */
    @Scheduled(fixedDelayString = "${builder.dataplane.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        if (instanceId == null || baseUrl == null) {
            return;
        }
        if (internalToken == null || internalToken.isBlank()) {
            return;
        }
        if (!registered.get()) {
            tryRegister();
            return;
        }
        try {
            controlPlaneClient.heartbeatDataPlane(instanceId);
        } catch (Exception ex) {
            log.warn(
                    "Data-plane heartbeat failed for {}; will re-register: {}",
                    instanceId,
                    ex.getMessage());
            registered.set(false);
            tryRegister();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!registered.get() || instanceId == null) {
            return;
        }
        try {
            controlPlaneClient.deleteDataPlane(instanceId);
            log.info("Unregistered data-plane instance {}", instanceId);
        } catch (Exception ex) {
            log.warn("Failed to unregister data-plane {}: {}", instanceId, ex.getMessage());
        } finally {
            registered.set(false);
        }
    }

    private void tryRegister() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        body.put("instanceId", instanceId);
        body.put("baseUrl", baseUrl);
        body.put("runtime", "agentscope-java");
        body.put("framework", "agentscope-java");
        body.put("contractLevel", 3);
        body.put("capabilities", CAPABILITIES);
        body.put("source", "self-register");
        try {
            long interval = controlPlaneClient.registerDataPlane(body);
            registered.set(true);
            log.info(
                    "Registered data-plane {} at {} with aistiod (heartbeat ~{}s)",
                    instanceId,
                    baseUrl,
                    interval);
        } catch (Exception ex) {
            registered.set(false);
            log.warn(
                    "Data-plane self-registration failed (will retry on heartbeat): {}",
                    ex.getMessage());
        }
    }

    private String resolveInstanceId() {
        if (configuredInstanceId != null && !configuredInstanceId.isBlank()) {
            return configuredInstanceId.trim();
        }
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // fall through
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String resolveBaseUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return trimTrailingSlash(publicUrl.trim());
        }
        return "http://localhost:" + serverPort;
    }

    private static String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
