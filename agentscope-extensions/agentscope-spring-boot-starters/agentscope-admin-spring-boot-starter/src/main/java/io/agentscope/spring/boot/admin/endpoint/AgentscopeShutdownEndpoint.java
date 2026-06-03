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
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code POST /actuator/agentscope-shutdown}: drain, then schedule a JVM-level shutdown.
 *
 * <p>Distinct from Spring Boot's own {@code /actuator/shutdown} in that it first calls
 * {@link GracefulShutdownManager#performGracefulShutdown()} so any in-flight reasoning gets a
 * chance to checkpoint its {@link io.agentscope.core.state.AgentState}, only then closing the
 * Spring {@link ConfigurableApplicationContext}.
 *
 * <p>The actual {@code System.exit} or container teardown is left to the container — this method
 * closes the Spring context on a background thread and returns immediately.
 */
@Endpoint(id = "agentscope-shutdown")
public class AgentscopeShutdownEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeShutdownEndpoint.class);

    private final AdminProperties properties;
    private final AdminAuditLogger audit;
    private final ApplicationContext appContext;

    public AgentscopeShutdownEndpoint(
            AdminProperties properties, AdminAuditLogger audit, ApplicationContext appContext) {
        this.properties = properties;
        this.audit = audit;
        this.appContext = appContext;
    }

    @WriteOperation
    public Map<String, Object> shutdown(String confirm, String token, Long awaitMillis) {
        WriteGuardSupport.check(properties, token, confirm);
        long await = awaitMillis == null ? 30_000L : Math.max(0L, awaitMillis);

        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        m.performGracefulShutdown();
        boolean drained = m.awaitTermination(Duration.ofMillis(await));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("await_millis", await);
        attrs.put("drained", drained);
        attrs.put("active_requests_remaining", m.getActiveRequestCount());
        audit.record(
                "system.shutdown",
                /* operator */ null,
                /* target */ "process",
                /* writes */ true,
                /* outcome */ drained ? "ok" : "timeout",
                attrs);

        log.warn(
                "agentscope-shutdown invoked; drained={} remaining={} — closing application"
                        + " context",
                drained,
                m.getActiveRequestCount());

        // Close the Spring context asynchronously so the HTTP response can return first.
        if (appContext instanceof ConfigurableApplicationContext closable) {
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(250L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                try {
                                    closable.close();
                                } catch (RuntimeException e) {
                                    log.error("Failed to close application context", e);
                                }
                            },
                            "agentscope-admin-shutdown");
            t.setDaemon(false);
            t.start();
        }

        Map<String, Object> out = new LinkedHashMap<>(attrs);
        out.put("message", "shutdown scheduled");
        return out;
    }
}
