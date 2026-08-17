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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * {@code POST /actuator/agentscope-drain}: stop accepting new agent requests, wait for in-flight
 * ones to finish, but do NOT terminate the JVM.
 *
 * <p>This is the production-friendly "let me bring this node out of the load balancer" knob:
 * health probes (e.g. {@code /actuator/health}) can flip to red, in-flight reasoning completes
 * naturally, and the process stays alive for log collection.
 *
 * <p>Drain is idempotent — second invocation is a no-op. Because Actuator's {@code @WriteOperation}
 * does not surface raw HTTP headers, the token check is enforced via the {@code confirm}
 * parameter; production deployments should layer Spring Security on top of {@code /actuator/**}.
 */
@Endpoint(id = "agentscope-drain")
public class AgentscopeDrainEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeDrainEndpoint.class);

    private final AdminProperties properties;
    private final AdminAuditLogger audit;

    public AgentscopeDrainEndpoint(AdminProperties properties, AdminAuditLogger audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @ReadOperation
    public Map<String, Object> currentDrainStatus() {
        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", m.getState().name());
        out.put("accepting_requests", m.isAcceptingRequests());
        out.put("active_requests", m.getActiveRequestCount());
        return out;
    }

    @WriteOperation
    public Map<String, Object> drain(String confirm, String token) {
        WriteGuardSupport.check(properties, token, confirm);
        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        ShutdownState stateBefore = m.getState();
        boolean triggered = false;
        if (stateBefore == ShutdownState.RUNNING) {
            triggered = m.performGracefulShutdown();
            log.warn(
                    "agentscope-drain invoked; performGracefulShutdown returned {}; active={}",
                    triggered,
                    m.getActiveRequestCount());
        } else {
            log.info("agentscope-drain invoked but already in state {}", stateBefore);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("state_before", stateBefore.name());
        attrs.put("triggered", triggered);
        audit.record(
                "system.drain",
                /* operator */ null,
                /* target */ "process",
                /* writes */ true,
                /* outcome */ "ok",
                attrs);
        Map<String, Object> out = new LinkedHashMap<>(attrs);
        out.put("state_after", m.getState().name());
        out.put("active_requests", m.getActiveRequestCount());
        return out;
    }
}
