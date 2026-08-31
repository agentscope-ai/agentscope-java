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
package io.agentscope.claw2.web.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.extensions.aistio.transport.HttpSelfRegistration;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional aistio BYO hook: instrument the main HarnessAgent, serve {@code /agentscope/*}, and
 * HTTP self-register with a standalone aistiod ({@code POST /api/v1/agent-registrations}).
 *
 * <p>Enable with {@code claw.aistio.enabled=true} (off by default). Local-dev defaults for
 * control URL, internal token, and contract port live in {@code application.yml}.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "claw.aistio",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AistioRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(AistioRegistrationConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final List<SessionBridge> bridges = new ArrayList<>();

    @Value("${claw.aistio.control-http:http://localhost:8081}")
    private String controlHttp;

    @Value("${claw.aistio.control-grpc:localhost:15010}")
    private String controlGrpc;

    @Value("${claw.aistio.internal-token:}")
    private String internalToken;

    @Value("${claw.aistio.registration-credential:}")
    private String configuredRegistrationCredential;

    @Value("${claw.aistio.agent-name:agentscope-paw}")
    private String agentName;

    @Value("${claw.aistio.tenant:default}")
    private String tenant;

    @Value("${claw.aistio.namespace:default}")
    private String namespace;

    @Value("${claw.aistio.contract-port:18090}")
    private int contractPort;

    @Value("${claw.aistio.public-base-url:}")
    private String publicBaseUrl;

    @Value("${claw.aistio.enable-events:false}")
    private boolean enableEvents;

    /**
     * Created ahead of {@code ClawBootstrap} so its observer middleware can be registered while the
     * agents are being built; see {@code BuilderConfig#builderBootstrap}.
     */
    @Bean
    public AgentScopeAdapter aistioAdapter() {
        return new AgentScopeAdapter();
    }

    @Bean
    public SessionBridge aistioSessionBridge(ClawBootstrap bootstrap, AgentScopeAdapter adapter) {
        HarnessAgent main = bootstrap.mainAgent();
        Path credentialFile = bootstrap.clawHome().resolve(".aistio").resolve("registration.json");
        String registrationCredential = configuredRegistrationCredential;
        if (registrationCredential == null || registrationCredential.isBlank()) {
            registrationCredential =
                    loadRegistrationCredential(credentialFile, tenant, namespace, agentName);
        }
        AistioConfig.Builder cfg =
                AistioConfig.builder(agentName)
                        .controlPlaneHttp(controlHttp)
                        .controlPlane(controlGrpc)
                        .internalToken(internalToken == null ? "" : internalToken)
                        .registrationCredential(registrationCredential)
                        .tenant(tenant)
                        .namespace(namespace)
                        .contractHttpPort(contractPort)
                        .enableEvents(enableEvents)
                        .startGrpc(true)
                        .startHttp(true);

        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            cfg.publicBaseUrl(publicBaseUrl);
        }

        boolean hasBootstrapToken = internalToken != null && !internalToken.isBlank();
        boolean hasRegistrationCredential =
                registrationCredential != null && !registrationCredential.isBlank();
        if (!hasBootstrapToken && !hasRegistrationCredential) {
            // Still serve /agentscope/* so local probing works; skip register.
            cfg.startHttpRegister(false);
            log.warn(
                    "claw.aistio: no bootstrap token or persisted registration credential;"
                            + " contract HTTP on :{} but not registering with {}",
                    contractPort,
                    controlHttp);
        } else {
            cfg.startHttpRegister(true);
        }

        String token =
                internalToken == null || internalToken.isBlank()
                        ? "builder-internal-dev-token"
                        : internalToken;
        adapter.setAgentTaskStarter(
                new HarnessAgentTaskStarter(
                        bootstrap::mainAgent,
                        new CollaborationClient(new ControlPlaneHttpClient(controlHttp, token))));

        SessionBridge bridge = Aistio.instrument(main, cfg.build(), adapter);
        HttpSelfRegistration.RegisteredIdentity registered = bridge.getRegisteredIdentity();
        if (registered != null && !registered.registrationCredential().isBlank()) {
            persistRegistrationCredential(
                    credentialFile,
                    tenant,
                    namespace,
                    agentName,
                    registered.registrationCredential());
        }
        adapter.setHistorySource(new HarnessSessionHistorySource(main));
        adapter.setRuntimeSource(new HarnessAgentRuntimeSource(main));
        bridges.add(bridge);
        log.info(
                "claw.aistio: instrumented main agent as '{}' (agentId={}, contract :{}, control"
                        + " {}, agent-task=on)",
                agentName,
                main.getAgentId(),
                bridge.getContractPort(),
                controlHttp);
        if (main.getAgentId() != null
                && !main.getAgentId().isBlank()
                && !main.getAgentId().equals(agentName)) {
            log.warn(
                    "claw.aistio: Catalog agentKey '{}' differs from runtime-local harness agentId"
                            + " '{}'. Task dispatch is unaffected; Operate transcript lookup still"
                            + " needs an explicit Catalog-to-runtime identity mapping.",
                    agentName,
                    main.getAgentId());
        }
        return bridge;
    }

    private static String loadRegistrationCredential(
            Path file, String tenant, String namespace, String agentKey) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            JsonNode stored = JSON.readTree(file.toFile());
            if (tenant.equals(stored.path("tenant").asText())
                    && namespace.equals(stored.path("namespace").asText())
                    && agentKey.equals(stored.path("agentKey").asText())) {
                return stored.path("registrationCredential").asText("");
            }
            log.warn(
                    "claw.aistio: ignoring registration credential for a different identity in {}",
                    file);
        } catch (IOException e) {
            log.warn("claw.aistio: failed to read registration credential from {}", file, e);
        }
        return "";
    }

    private static void persistRegistrationCredential(
            Path file,
            String tenant,
            String namespace,
            String agentKey,
            String registrationCredential) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "registration-", ".json");
            ObjectNode stored = JSON.createObjectNode();
            stored.put("tenant", tenant);
            stored.put("namespace", namespace);
            stored.put("agentKey", agentKey);
            stored.put("registrationCredential", registrationCredential);
            Files.writeString(
                    temporary,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(stored),
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.setPosixFilePermissions(temporary, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems still inherit the Paw home directory's access controls.
            }
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException e) {
            log.warn("claw.aistio: failed to persist registration credential to {}", file, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a never-published credential file.
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SessionBridge b : bridges) {
            try {
                b.close();
            } catch (Exception e) {
                log.debug("claw.aistio: bridge close failed: {}", e.getMessage());
            }
        }
        bridges.clear();
    }
}
