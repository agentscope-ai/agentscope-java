/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server;

import io.a2a.server.events.QueueManager;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.card.AgentScopeAgentCardConverter;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.AgentScopeAgentExecutor;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.ReActAgentWithBuilderRunner;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.registry.AgentRegistryService;
import io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;
import io.agentscope.core.agent.Agent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agentscope A2a Server.
 *
 * <p>The core enter class for developer to export agent as A2A Server by agentscope, Developers should build server
 * with:
 * <ul>
 *     <li>Required: Custom {@link Agent} such as {@link ReActAgent}. </li>
 *     <li>Optional: Custom AgentCard. </li>
 *     <li>Optional: Choose supported to export transport, such as `JSONRPC`. </li>
 *     <li>Optional: A2A Registry(s) when server ready to access request. </li>
 *     <li>Optional: A2A Server Feature implementations, {@link TaskStore}, {@link QueueManager},
 *     {@link PushNotificationConfigStore}, {@link PushNotificationSender}.</li>
 * </ul>
 *
 * <p>The Server is not listen ports and export endpoints. The main logic for this Server class is build and assemble
 * all component for handle a2a request.
 *
 * <p>For example, Developers want to export JSON-RPC transport for A2A server, they should do follow the simplest steps:
 * <ol>
 *     <li>Build Custom {@link Agent}, such as {@link ReActAgent} with {@link ReActAgent.Builder}.</li>
 *     <li>Create Builder of {@link AgentScopeA2aServer} with required {@link ReActAgent.Builder}.</li>
 *     <li>Build {@link AgentScopeA2aServer} instance and hold it.</li>
 *     <li>Export web server with custom port and custom Controller to access web JSON-RPC request.</li>
 *     <li>call {@link #postEndpointReady()} when web server ready.</li>
 *     <li>Get {@link TransportWrapper} implementation for JSON-RPC from this {@link AgentScopeA2aServer} to actual
 *     handle request when client request received.</li>
 * </ol>
 */
@SuppressWarnings("rawtypes")
public class AgentScopeA2aServer {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeA2aServer.class);

    private final Map<String, TransportWrapper> transportWrappers;

    private final Set<TransportProperties> transportProperties;

    private final AgentCard agentCard;

    private final AgentRegistryService agentRegistry;

    private AgentScopeA2aServer(
            Map<String, TransportWrapper> transportWrappers,
            Set<TransportProperties> transportProperties,
            AgentCard agentCard,
            AgentRegistryService agentRegistry) {
        this.transportWrappers = transportWrappers;
        this.transportProperties = transportProperties;
        this.agentCard = agentCard;
        this.agentRegistry = agentRegistry;
    }

    /**
     * Get a raw type {@link TransportWrapper} instance by its name. Developers should judge type whether expected their
     * expected before actual use.
     *
     * @param transportName transport name
     * @return a raw type {@link TransportWrapper} instance
     */
    public TransportWrapper getTransportWrapper(String transportName) {
        TransportWrapper result = transportWrappers.get(transportName);
        if (null == result) {
            throw new IllegalArgumentException("Transport " + transportName + " not found.");
        }
        return result;
    }

    /**
     * Get a specific {@link TransportWrapper} instance by its name and expected type.
     *
     * @param transportName transport name
     * @param expectedType  expected class {@link TransportWrapper}
     * @param <W>           subtype of {@link TransportWrapper}
     * @return {@link TransportWrapper} instance with expected type
     * @throws ClassCastException If the found wrapper is not the expected type
     */
    public <W extends TransportWrapper<?, ?>> W getTransportWrapper(
            String transportName, Class<W> expectedType) {
        TransportWrapper rawWrapper = getTransportWrapper(transportName);
        if (expectedType.isInstance(rawWrapper)) {
            return expectedType.cast(rawWrapper);
        } else {
            throw new ClassCastException(
                    "Transport '"
                            + transportName
                            + "' is of type "
                            + rawWrapper.getClass().getName()
                            + ", not the expected type "
                            + expectedType.getName());
        }
    }

    /**
     * Get {@link AgentCard} instance for this server.
     *
     * @return agent card
     */
    public AgentCard getAgentCard() {
        return agentCard;
    }

    /**
     * Call this method when all endpoint ready to access.
     *
     * <p>By default, it should be called after web server ready or other network endpoint ready. To let AgentScope end
     * some A2A Server operation such as register A2A Registry,
     */
    public void postEndpointReady() {
        agentRegistry.register(agentCard, transportProperties);
    }

    /**
     * Get a Builder of {@link AgentScopeA2aServer} from {@link ReActAgent.Builder}.
     *
     * <p>For Most situation, will use default {@link ReActAgentWithBuilderRunner} as the {@link AgentRunner}
     *
     * @param agentBuilder builder of {@link ReActAgent}
     * @return builder instance of {@link AgentScopeA2aServer}
     * @see #builder(AgentRunner)
     */
    public static Builder builder(ReActAgent.Builder agentBuilder) {
        return builder(ReActAgentWithBuilderRunner.newInstance(agentBuilder));
    }

    /**
     * Get a Builder of {@link AgentScopeA2aServer} from custom {@link AgentRunner}.
     *
     * @param agentRunner custom agent runner
     * @return builder instance of {@link AgentScopeA2aServer}
     */
    public static Builder builder(AgentRunner agentRunner) {
        return new Builder(agentRunner);
    }

    public static class Builder {

        private final AgentRunner agentRunner;

        private final Set<TransportProperties> supportedTransports;

        private final List<AgentRegistry> agentRegistries;

        private ConfigurableAgentCard agentCard;

        private TaskStore taskStore;

        private QueueManager queueManager;

        private PushNotificationConfigStore pushConfigStore;

        private PushNotificationSender pushSender;

        private Executor executor;

        private DeploymentProperties deploymentProperties;

        private AgentExecuteProperties agentExecuteProperties;

        private Builder(AgentRunner agentRunner) {
            this.agentRunner = agentRunner;
            this.supportedTransports = new HashSet<>();
            this.agentRegistries = new LinkedList<>();
        }

        /**
         * Set developer configured agent card.
         *
         * @param agentCard configured agent card
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder agentCard(ConfigurableAgentCard agentCard) {
            this.agentCard = agentCard;
            return this;
        }

        /**
         * Add supported transport for this A2A server, and input this transport's properties.
         *
         * @param transportProperties properties of supported transport
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder withTransport(TransportProperties transportProperties) {
            supportedTransports.add(transportProperties);
            return this;
        }

        /**
         * Set implementations of {@link TaskStore}.
         *
         * <p> use {@link io.a2a.server.tasks.InMemoryTaskStore} by default.
         *
         * @param taskStore task store
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /**
         * Set implementations of {@link QueueManager}.
         *
         * <p> use {@link io.a2a.server.events.InMemoryQueueManager} by default.
         *
         * @param queueManager queue manager
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder queueManager(QueueManager queueManager) {
            this.queueManager = queueManager;
            return this;
        }

        /**
         * Set implementations of {@link PushNotificationConfigStore}.
         *
         * <p> use {@link io.a2a.server.tasks.InMemoryPushNotificationConfigStore} by default.
         *
         * @param pushConfigStore push notification config store
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder pushConfigStore(PushNotificationConfigStore pushConfigStore) {
            this.pushConfigStore = pushConfigStore;
            return this;
        }

        /**
         * Set implementations of {@link PushNotificationSender}.
         *
         * <p> use {@link io.a2a.server.tasks.BasePushNotificationSender} by default.
         *
         * @param pushSender push notification sender
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder pushSender(PushNotificationSender pushSender) {
            this.pushSender = pushSender;
            return this;
        }

        /**
         * Set executor for handle a2a request.
         *
         * <p> use {@link Executors#newCachedThreadPool()} by default.
         *
         * @param executor executor
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Set deployment port to generate default transport interface information in agentCard.
         *
         * @param port deployment port
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder deploymentProperties(Integer port) {
            this.deploymentProperties = new DeploymentProperties.Builder().port(port).build();
            return this;
        }

        /**
         * Set deployment properties to generate default transport interface information in agentCard.
         *
         * @param deploymentProperties deployment properties
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder deploymentProperties(DeploymentProperties deploymentProperties) {
            this.deploymentProperties = deploymentProperties;
            return this;
        }

        /**
         * Add {@link AgentRegistry} implementation which should register this agent to registry.
         *
         * @param agentRegistry agent registry implementation
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder withAgentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistries.add(agentRegistry);
            return this;
        }

        /**
         * Set agent execute properties.
         *
         * @param agentExecuteProperties agent execute properties
         * @return builder instance of {@link AgentScopeA2aServer}
         */
        public Builder agentExecuteProperties(AgentExecuteProperties agentExecuteProperties) {
            this.agentExecuteProperties = agentExecuteProperties;
            return this;
        }

        /**
         * Build a new instance of {@link AgentScopeA2aServer}.
         *
         * <p> This Builder will build with follow steps:
         * <ul>
         *     <li>Build {@link AgentCard} from input agent card properties and transports properties.</li>
         *     <li>Build AgentExecutor with input {@link AgentRunner} and build RequestHandler.</li>
         *     <li>Build all transport request handle wrapper by input properties and SPI load transport builders</li>
         *     <li>Build {@link AgentRegistryService} from input {@link AgentRegistry}.</li>
         * </ul>
         *
         * @return a new instance of {@link AgentScopeA2aServer}
         * @throws IllegalArgumentException if {@link AgentRunner} is missing or no available transport found.
         */
        public AgentScopeA2aServer build() {
            if (null == agentRunner) {
                throw new IllegalArgumentException("AgentRunner is required.");
            }
            if (null == agentExecuteProperties) {
                agentExecuteProperties = AgentExecuteProperties.builder().build();
            }
            AgentScopeAgentExecutor agentExecutor =
                    new AgentScopeAgentExecutor(agentRunner, agentExecuteProperties);
            AgentScopeA2aRequestHandler requestHandler =
                    AgentScopeA2aRequestHandler.builder()
                            .agentExecutor(agentExecutor)
                            .taskStore(taskStore)
                            .queueManager(queueManager)
                            .pushConfigStore(pushConfigStore)
                            .pushSender(pushSender)
                            .build();
            if (null == executor) {
                executor = Executors.newCachedThreadPool();
            }
            if (null == agentCard) {
                agentCard = new ConfigurableAgentCard.Builder().build();
            }
            if (supportedTransports.isEmpty()) {
                log.warn("Not found input supportedTransports, use default transport: `JSONRPC`");
                if (null == deploymentProperties) {
                    log.error(
                            "Not found any properties for transport and not found default"
                                    + " deployment properties.");
                    throw new IllegalArgumentException("Not found any properties for transport.");
                }
                supportedTransports.add(
                        TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                                .host(deploymentProperties.host())
                                .port(deploymentProperties.port())
                                .build());
            }
            Map<String, TransportWrapperBuilder> allBuilders = loadTransportBuilders();
            Set<TransportProperties> availableTransports = getAvailableTransports(allBuilders);
            if (availableTransports.isEmpty()) {
                throw new IllegalArgumentException("No one available transport found.");
            }
            AgentCard a2aAgentCard =
                    new AgentScopeAgentCardConverter()
                            .createAgentCard(agentCard, agentRunner, availableTransports);
            Map<String, TransportWrapper> transportWrappers =
                    buildTransportWrappers(
                            requestHandler, availableTransports, allBuilders, a2aAgentCard);
            if (transportWrappers.isEmpty()) {
                log.warn("No one TransportWrapper actually, a2a request will not be handle.");
            }
            AgentRegistryService agentRegistryService = new AgentRegistryService(agentRegistries);
            return new AgentScopeA2aServer(
                    transportWrappers, availableTransports, a2aAgentCard, agentRegistryService);
        }

        private Map<String, TransportWrapperBuilder> loadTransportBuilders() {
            return ServiceLoader.load(TransportWrapperBuilder.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(builder -> null != builder.getTransportType())
                    .collect(
                            Collectors.toMap(
                                    TransportWrapperBuilder::getTransportType,
                                    Function.identity(),
                                    (oldBuilder, newBuilder) -> {
                                        log.warn(
                                                "Load duplicate TransportWrapperBuilder for"
                                                    + " transport `{}`, oldType: {}, newType: {}",
                                                oldBuilder.getTransportType(),
                                                oldBuilder.getClass().getName(),
                                                newBuilder.getClass().getName());
                                        return newBuilder;
                                    }));
        }

        private Set<TransportProperties> getAvailableTransports(
                Map<String, TransportWrapperBuilder> allBuilders) {
            return supportedTransports.stream()
                    .filter(
                            transportProperties -> {
                                String transportType = transportProperties.transportType();
                                if (allBuilders.containsKey(transportType)) {
                                    return true;
                                }
                                log.warn(
                                        "Not found TransportWrapperBuilder for configure supported"
                                                + " transport `{}` from SPI, will ignore this"
                                                + " transport",
                                        transportType);
                                return false;
                            })
                    .collect(Collectors.toSet());
        }

        private Map<String, TransportWrapper> buildTransportWrappers(
                AgentScopeA2aRequestHandler requestHandler,
                Set<TransportProperties> availableTransports,
                Map<String, TransportWrapperBuilder> allBuilders,
                AgentCard a2aAgentCard) {
            Map<String, TransportWrapper> result = new HashMap<>();
            Set<TransportProperties> buildFailedTransport = new HashSet<>();
            for (TransportProperties transportProperties : availableTransports) {
                TransportWrapperBuilder builder =
                        allBuilders.get(transportProperties.transportType());
                try {
                    TransportWrapper transportWrapper =
                            builder.build(a2aAgentCard, requestHandler, executor);
                    result.put(transportProperties.transportType(), transportWrapper);
                } catch (Exception e) {
                    log.warn(
                            "Build TransportWrapper for configure supported transport `{}` failed,"
                                    + " will ignore this transport",
                            transportProperties.transportType(),
                            e);
                    buildFailedTransport.add(transportProperties);
                }
            }
            availableTransports.removeAll(buildFailedTransport);
            return result;
        }
    }
}
