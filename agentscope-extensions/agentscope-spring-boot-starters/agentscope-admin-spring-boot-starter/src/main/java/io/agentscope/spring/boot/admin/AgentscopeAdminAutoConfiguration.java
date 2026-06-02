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
package io.agentscope.spring.boot.admin;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.command.AdminCommandRegistry;
import io.agentscope.spring.boot.admin.command.BuiltinCommandRegistrar;
import io.agentscope.spring.boot.admin.controller.SessionAdminController;
import io.agentscope.spring.boot.admin.controller.SubagentTaskController;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeAgentsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeCommandsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeDoctorEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeDrainEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeModelsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopePermissionsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeShutdownEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeStatusEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeSubagentsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeToolsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeUsageEndpoint;
import io.agentscope.spring.boot.admin.metrics.MetricsHook;
import io.agentscope.spring.boot.admin.metrics.MetricsRecorder;
import io.agentscope.spring.boot.admin.openapi.AdminOpenApiConfiguration;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.agentscope.spring.boot.admin.registry.InMemoryAgentRegistry;
import io.agentscope.spring.boot.admin.service.AgentInventory;
import io.agentscope.spring.boot.admin.service.ModelSummarizationStrategy;
import io.agentscope.spring.boot.admin.service.SessionOperations;
import io.agentscope.spring.boot.admin.service.SubagentTaskOperations;
import io.agentscope.spring.boot.admin.service.SummarizationStrategy;
import io.agentscope.spring.boot.admin.snapshot.SnapshotStore;
import io.agentscope.spring.boot.admin.subagent.SpringSubagentInventory;
import io.agentscope.spring.boot.admin.subagent.SubagentInventory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for the AgentScope admin/ops starter.
 *
 * <p>Activation rules:
 *
 * <ul>
 *   <li>{@code agentscope.admin.enabled=true} — master switch (defaults to false so this starter
 *       never accidentally exposes admin endpoints just by being on the classpath).
 *   <li>{@link Agent} on the classpath — guards against running without agentscope-core.
 * </ul>
 *
 * <p>Web controllers register only when a Servlet web application is detected
 * ({@link ConditionalOnWebApplication}). Actuator endpoints register only when the Actuator
 * {@code Endpoint} type is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(Agent.class)
@ConditionalOnProperty(prefix = "agentscope.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
@org.springframework.context.annotation.Import(AdminOpenApiConfiguration.class)
public class AgentscopeAdminAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeAdminAutoConfiguration.class);

    /**
     * Default {@link AgentRegistry}. Auto-seeded from any singleton {@link Agent} beans found in
     * the context — applications using prototype-scoped agents (the AgentScope default) must call
     * {@link AgentRegistry#register(Agent)} themselves when they create an agent.
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentscopeAgentRegistry(ObjectProvider<Map<String, Agent>> agentBeans) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        Map<String, Agent> beans = agentBeans.getIfAvailable();
        if (beans != null) {
            for (Map.Entry<String, Agent> entry : beans.entrySet()) {
                try {
                    registry.register(entry.getValue());
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to seed AgentRegistry from bean {}: {}",
                            entry.getKey(),
                            e.toString());
                }
            }
            log.info("Seeded AgentRegistry with {} singleton agent bean(s)", beans.size());
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCommandRegistry agentscopeAdminCommandRegistry() {
        return new AdminCommandRegistry();
    }

    @Bean
    public BuiltinCommandRegistrar agentscopeBuiltinCommandRegistrar(
            AdminCommandRegistry registry, AdminProperties properties) {
        return new BuiltinCommandRegistrar(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminAuditLogger agentscopeAdminAuditLogger(
            AdminProperties properties, ApplicationEventPublisher publisher) {
        return new AdminAuditLogger(properties, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarizationStrategy agentscopeSummarizationStrategy() {
        return new ModelSummarizationStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotStore agentscopeSnapshotStore() {
        return new SnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionOperations agentscopeSessionOperations(
            AgentRegistry registry,
            SummarizationStrategy summarizer,
            AdminProperties properties,
            SnapshotStore snapshots) {
        return new SessionOperations(registry, summarizer, properties, snapshots);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInventory agentscopeAgentInventory(
            AgentRegistry registry,
            ObjectProvider<Map<String, Toolkit>> toolkits,
            ObjectProvider<Map<String, Model>> models) {
        return new AgentInventory(registry, toolkits, models);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentInventory agentscopeSubagentInventory(
            ObjectProvider<Map<String, SubagentEntry>> entryBeans,
            ObjectProvider<Map<String, SubagentDeclaration>> declarationBeans) {
        return new SpringSubagentInventory(entryBeans, declarationBeans);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentTaskOperations agentscopeSubagentTaskOperations(
            ObjectProvider<TaskRepository> repoProvider) {
        return new SubagentTaskOperations(repoProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder agentscopeMetricsRecorder() {
        return new MetricsRecorder();
    }

    /**
     * Registers a {@link MetricsHook} into {@link AgentBase#addSystemHook(Hook)}
     * at startup so every subsequently constructed agent contributes token-usage counters.
     *
     * <p>The hook is unregistered on context close, so reloading the starter does not double-count.
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsHookLifecycle agentscopeMetricsHookLifecycle(MetricsRecorder recorder) {
        return new MetricsHookLifecycle(new MetricsHook(recorder));
    }

    /** Tiny lifecycle bean — wires/unwires the metrics hook with the application context. */
    public static final class MetricsHookLifecycle {

        private static final Logger logger = LoggerFactory.getLogger(MetricsHookLifecycle.class);

        private final MetricsHook hook;

        public MetricsHookLifecycle(MetricsHook hook) {
            this.hook = hook;
        }

        @PostConstruct
        public void register() {
            AgentBase.addSystemHook(hook);
            logger.info(
                    "Registered MetricsHook as AgentBase system hook (applies to agents constructed"
                            + " after this point)");
        }

        @PreDestroy
        public void unregister() {
            AgentBase.removeSystemHook(hook);
        }

        public MetricsHook hook() {
            return hook;
        }
    }

    /**
     * Data-plane REST surface. Only loaded for Servlet web applications.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public static class WebMvcConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SessionAdminController agentscopeSessionAdminController(
                SessionOperations ops,
                ObjectProvider<Session> sessions,
                AdminAuditLogger audit,
                AdminProperties properties) {
            return new SessionAdminController(ops, sessions, audit, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public SubagentTaskController agentscopeSubagentTaskController(
                SubagentTaskOperations ops, AdminAuditLogger audit, AdminProperties properties) {
            return new SubagentTaskController(ops, audit, properties);
        }
    }

    /**
     * Control-plane Actuator endpoints. Loaded only when Spring Boot Actuator's
     * {@code @Endpoint} type is on the classpath; each endpoint registration still respects the
     * standard {@code management.endpoint.<id>.enabled} / {@code management.endpoints.web.exposure}
     * conventions.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    public static class ActuatorEndpointsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeStatusEndpoint agentscopeStatusEndpoint(
                AgentInventory inventory, AdminProperties properties) {
            return new AgentscopeStatusEndpoint(inventory, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeAgentsEndpoint agentscopeAgentsEndpoint(AgentInventory inventory) {
            return new AgentscopeAgentsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeToolsEndpoint agentscopeToolsEndpoint(AgentInventory inventory) {
            return new AgentscopeToolsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeModelsEndpoint agentscopeModelsEndpoint(AgentInventory inventory) {
            return new AgentscopeModelsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeCommandsEndpoint agentscopeCommandsEndpoint(
                AdminCommandRegistry registry) {
            return new AgentscopeCommandsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeDoctorEndpoint agentscopeDoctorEndpoint(
                AdminProperties properties,
                AgentInventory inventory,
                ObjectProvider<Session> sessions) {
            return new AgentscopeDoctorEndpoint(properties, inventory, sessions);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeUsageEndpoint agentscopeUsageEndpoint(MetricsRecorder recorder) {
            return new AgentscopeUsageEndpoint(recorder);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopePermissionsEndpoint agentscopePermissionsEndpoint(AgentRegistry registry) {
            return new AgentscopePermissionsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeDrainEndpoint agentscopeDrainEndpoint(
                AdminProperties properties, AdminAuditLogger audit) {
            return new AgentscopeDrainEndpoint(properties, audit);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeShutdownEndpoint agentscopeShutdownEndpoint(
                AdminProperties properties, AdminAuditLogger audit, ApplicationContext context) {
            return new AgentscopeShutdownEndpoint(properties, audit, context);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeSubagentsEndpoint agentscopeSubagentsEndpoint(
                SubagentInventory inventory) {
            return new AgentscopeSubagentsEndpoint(inventory);
        }
    }
}
