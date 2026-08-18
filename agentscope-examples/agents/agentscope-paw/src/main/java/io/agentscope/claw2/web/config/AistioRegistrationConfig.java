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

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.adapter.HarnessTeamSessionStarter;
import io.agentscope.extensions.aistio.store.ControlPlaneTeamClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.team.TeamClient;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional aistio BYO hook: instrument the main HarnessAgent, serve {@code /agentscope/*}, and
 * HTTP self-register with a standalone aistiod ({@code POST /api/v1/dataplanes/register}).
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

    private final List<SessionBridge> bridges = new ArrayList<>();

    @Value("${claw.aistio.control-http:http://localhost:8081}")
    private String controlHttp;

    @Value("${claw.aistio.internal-token:}")
    private String internalToken;

    @Value("${claw.aistio.agent-name:agentscope-paw}")
    private String agentName;

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
        AistioConfig.Builder cfg =
                AistioConfig.builder(agentName)
                        .controlPlaneHttp(controlHttp)
                        .internalToken(internalToken == null ? "" : internalToken)
                        .namespace(namespace)
                        .contractHttpPort(contractPort)
                        .enableEvents(enableEvents)
                        .startGrpc(false)
                        .startHttp(true);

        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            cfg.publicBaseUrl(publicBaseUrl);
        }

        if (internalToken == null || internalToken.isBlank()) {
            // Still serve /agentscope/* so local probing works; skip register.
            cfg.startHttpRegister(false);
            log.warn(
                    "claw.aistio: BUILDER_INTERNAL_TOKEN / claw.aistio.internal-token is blank;"
                            + " contract HTTP on :{} but not registering with {}",
                    contractPort,
                    controlHttp);
        } else {
            cfg.startHttpRegister(true);
        }

        // Wire BYO team_join before instrument so register advertises team-coordination.
        String token =
                internalToken == null || internalToken.isBlank()
                        ? "builder-internal-dev-token"
                        : internalToken;
        TeamClient teamClient =
                new ControlPlaneTeamClient(new ControlPlaneHttpClient(controlHttp, token));
        adapter.setTeamSessionStarter(
                new HarnessTeamSessionStarter(bootstrap::mainAgent, teamClient));

        SessionBridge bridge = Aistio.instrument(main, cfg.build(), adapter);
        adapter.setHistorySource(new HarnessSessionHistorySource(main));
        adapter.setRuntimeSource(new HarnessAgentRuntimeSource(main));
        bridges.add(bridge);
        log.info(
                "claw.aistio: instrumented main agent as '{}' (agentId={}, contract :{}, control"
                        + " {}, team-coordination=on)",
                agentName,
                main.getAgentId(),
                bridge.getContractPort(),
                controlHttp);
        if (main.getAgentId() != null
                && !main.getAgentId().isBlank()
                && !main.getAgentId().equals(agentName)) {
            log.warn(
                    "claw.aistio: registered agent-name '{}' != harness agentId '{}'. Transcript"
                        + " segments are keyed by agentId; set claw.aistio.agent-name to '{}' (or"
                        + " CLAW_AISTIO_AGENT_NAME) so Operate FS reads hit the same path.",
                    agentName,
                    main.getAgentId(),
                    main.getAgentId());
        }
        return bridge;
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
