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
package io.agentscope.spring.boot.admin.audit;

import io.agentscope.spring.boot.admin.properties.AdminProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Default audit sink: logs at INFO via SLF4J and (optionally) republishes the record as a Spring
 * {@code ApplicationEvent} so other components can subscribe with {@code @EventListener}.
 *
 * <p>Replace with a custom {@code AdminAuditLogger} bean to ship records to RocketMQ, Loki, or a
 * compliance pipeline.
 */
public class AdminAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogger.class);

    private final AdminProperties properties;
    private final ApplicationEventPublisher publisher;

    public AdminAuditLogger(AdminProperties properties, ApplicationEventPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    public void record(
            String commandId,
            String operator,
            String target,
            boolean writes,
            String outcome,
            Map<String, Object> attributes) {
        AdminAuditEvent event =
                new AdminAuditEvent(null, commandId, operator, target, writes, outcome, attributes);
        log.info(
                "agentscope.admin command={} writes={} operator={} target={} outcome={} attrs={}",
                event.commandId(),
                event.writes(),
                event.operator(),
                event.target(),
                event.outcome(),
                event.attributes());
        if (properties.isPublishAuditEvents() && publisher != null) {
            publisher.publishEvent(event);
        }
    }
}
