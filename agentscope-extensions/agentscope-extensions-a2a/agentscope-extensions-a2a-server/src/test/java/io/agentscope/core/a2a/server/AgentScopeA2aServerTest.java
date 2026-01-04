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

package io.agentscope.core.a2a.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Builder of AgentScopeA2aServer.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>AgentScopeA2aServer Builder pattern functionality</li>
 *   <li>Server creation with various configurations by Builder</li>
 *   <li>AgentScopeA2aServer Builder method chaining</li>
 *   <li>Error handling for invalid configurations</li>
 * </ul>
 *
 * <p>In this Unit test, will only test to build AgentScopeA2aServer, about Unit test for using
 * AgentScopeA2aServer, see other tests.
 */
@DisplayName("AgentScopeA2aServer Builder Tests")
class AgentScopeA2aServerTest {

    private ReActAgent.Builder agentBuilder;
    private AgentRunner agentRunner;
    private TransportProperties transportProperties;
    private DeploymentProperties deploymentProperties;

    @BeforeEach
    void setUp() {
        agentBuilder = mock(ReActAgent.Builder.class);
        ReActAgent reActAgent = mock(ReActAgent.class);
        agentRunner = mock(AgentRunner.class);
        transportProperties = mock(TransportProperties.class);
        deploymentProperties = mock(DeploymentProperties.class);

        lenient()
                .when(transportProperties.transportType())
                .thenReturn(TransportProtocol.JSONRPC.asString());
        lenient().when(agentBuilder.build()).thenReturn(reActAgent);
        lenient().when(reActAgent.getName()).thenReturn("unit test agent");
        lenient().when(reActAgent.getDescription()).thenReturn("unit test description");
        lenient().when(agentRunner.getAgentName()).thenReturn("unit test agent");
        lenient().when(agentRunner.getAgentDescription()).thenReturn("unit test description");
    }

    @Nested
    @DisplayName("Builder Creation Tests")
    class BuilderCreationTests {

        @Test
        @DisplayName("Should create builder instance from ReActAgent.Builder")
        void testBuilderCreationFromReActAgentBuilder() {
            when(deploymentProperties.host()).thenReturn("localhost");
            when(deploymentProperties.port()).thenReturn(8080);
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentBuilder);
            assertNotNull(builder);
            AgentScopeA2aServer server = builder.deploymentProperties(deploymentProperties).build();
            assertNotNull(server);
        }

        @Test
        @DisplayName("Should create builder instance from AgentRunner")
        void testBuilderCreationFromAgentRunner() {
            when(deploymentProperties.host()).thenReturn("localhost");
            when(deploymentProperties.port()).thenReturn(8080);
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            assertNotNull(builder);
            AgentScopeA2aServer server = builder.deploymentProperties(deploymentProperties).build();
            assertNotNull(server);
        }

        @Test
        @DisplayName("Should build server with required parameters from AgentRunner")
        void testBuildServerWithRequiredParametersFromAgentRunner() {
            when(deploymentProperties.host()).thenReturn("localhost");
            when(deploymentProperties.port()).thenReturn(8080);

            AgentScopeA2aServer server =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties)
                            .build();

            assertNotNull(server);
        }

        @Test
        @DisplayName("Should throw exception when AgentRunner is missing")
        void testBuildWithoutAgentRunner() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentScopeA2aServer.builder((AgentRunner) null).build());
        }
    }

    @Nested
    @DisplayName("Builder Configuration Tests")
    class BuilderConfigurationTests {

        @Test
        @DisplayName("Should set agent card")
        void testAgentCard() throws Exception {
            ConfigurableAgentCard configurableAgentCard =
                    new ConfigurableAgentCard.Builder().build();

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            ;
            AgentScopeA2aServer.Builder result = builder.agentCard(configurableAgentCard);

            assertSame(builder, result);
            assertNotNull(builder.build().getAgentCard());
        }

        @Test
        @DisplayName("Should add supported transport")
        void testWithTransport() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            AgentScopeA2aServer.Builder result = builder.withTransport(transportProperties);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set task store")
        void testTaskStore() throws Exception {
            TaskStore taskStore = mock(TaskStore.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            ;
            AgentScopeA2aServer.Builder result = builder.taskStore(taskStore);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set queue manager")
        void testQueueManager() throws Exception {
            QueueManager queueManager = mock(QueueManager.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            ;
            AgentScopeA2aServer.Builder result = builder.queueManager(queueManager);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set push notification config store")
        void testPushConfigStore() throws Exception {
            PushNotificationConfigStore pushConfigStore = mock(PushNotificationConfigStore.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            ;
            AgentScopeA2aServer.Builder result = builder.pushConfigStore(pushConfigStore);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set push notification sender")
        void testPushSender() throws Exception {
            PushNotificationSender pushSender = mock(PushNotificationSender.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            ;
            AgentScopeA2aServer.Builder result = builder.pushSender(pushSender);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set executor")
        void testExecutor() throws Exception {
            Executor executor = mock(Executor.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            AgentScopeA2aServer.Builder result = builder.executor(executor);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set deployment properties")
        void testDeploymentProperties() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            AgentScopeA2aServer.Builder result = builder.deploymentProperties(deploymentProperties);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should set deployment properties with port only")
        void testDeploymentPropertiesWithSingleArg() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            AgentScopeA2aServer.Builder result = builder.deploymentProperties(8080);

            assertSame(builder, result);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should add agent registry")
        void testWithAgentRegistry() throws Exception {
            AgentRegistry agentRegistry = mock(AgentRegistry.class);

            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            AgentScopeA2aServer.Builder result = builder.withAgentRegistry(agentRegistry);

            assertSame(builder, result);
            builder.build().postEndpointReady();
            verify(agentRegistry).register(any(), any());
        }
    }

    @Nested
    @DisplayName("Builder Method Chaining Tests")
    class BuilderMethodChainingTests {

        @Test
        @DisplayName("Should support builder method chaining")
        void testBuilderMethodChaining() throws Exception {
            ConfigurableAgentCard configurableAgentCard =
                    new ConfigurableAgentCard.Builder().build();
            TaskStore taskStore = mock(TaskStore.class);
            QueueManager queueManager = mock(QueueManager.class);
            PushNotificationConfigStore pushConfigStore = mock(PushNotificationConfigStore.class);
            PushNotificationSender pushSender = mock(PushNotificationSender.class);
            Executor executor = mock(Executor.class);
            AgentRegistry agentRegistry = mock(AgentRegistry.class);

            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            AgentScopeA2aServer.Builder result =
                    builder.agentCard(configurableAgentCard)
                            .withTransport(transportProperties)
                            .taskStore(taskStore)
                            .queueManager(queueManager)
                            .pushConfigStore(pushConfigStore)
                            .pushSender(pushSender)
                            .executor(executor)
                            .deploymentProperties(deploymentProperties)
                            .agentExecuteProperties(AgentExecuteProperties.builder().build())
                            .withAgentRegistry(agentRegistry);

            assertNotNull(result);
            assertSame(builder, result);
        }
    }

    @Nested
    @DisplayName("Builder With Transports Tests")
    class BuilderWithTransportsTests {

        @Test
        @DisplayName("Should build transport wrapper with success")
        void testBuildTransportWrapperWithSuccess() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            TransportProperties transportProperties =
                    TransportProperties.builder("MOCK_SUCCESS").build();
            builder.withTransport(transportProperties);
            AgentScopeA2aServer agentScopeA2aServer = builder.build();
            assertNotNull(
                    agentScopeA2aServer.getTransportWrapper(
                            "MOCK_SUCCESS", TransportWrapper.class));
        }

        @Test
        @DisplayName("Should build transport wrapper with success but get with error type")
        void testBuildTransportWrapperWithSuccessButGetWithErrorType() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            TransportProperties transportProperties =
                    TransportProperties.builder("MOCK_SUCCESS").build();
            builder.withTransport(transportProperties);
            AgentScopeA2aServer agentScopeA2aServer = builder.build();
            assertThrows(
                    ClassCastException.class,
                    () ->
                            agentScopeA2aServer.getTransportWrapper(
                                    "MOCK_SUCCESS", JsonRpcTransportWrapper.class));
        }

        @Test
        @DisplayName("Should build transport wrapper with exception")
        void testBuildTransportWrapperWithException() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            TransportProperties transportProperties =
                    TransportProperties.builder("MOCK_EXCEPTION").build();
            builder.withTransport(transportProperties);
            AgentScopeA2aServer agentScopeA2aServer = builder.build();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> agentScopeA2aServer.getTransportWrapper("MOCK_EXCEPTION"));
        }

        @Test
        @DisplayName("Should build transport wrapper with no available transport")
        void testBuildTransportWrapperWithNoAvailableTransport() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            TransportProperties transportProperties =
                    TransportProperties.builder("NON_EXIST").build();
            builder.withTransport(transportProperties);
            assertThrows(IllegalArgumentException.class, builder::build);
        }

        @Test
        @DisplayName("Should build transport wrapper for default transport")
        void testBuildTransportWrapperForDefaultTransport() throws Exception {
            when(deploymentProperties.host()).thenReturn("localhost");
            when(deploymentProperties.port()).thenReturn(8080);
            AgentScopeA2aServer.Builder builder =
                    AgentScopeA2aServer.builder(agentRunner)
                            .deploymentProperties(deploymentProperties);
            assertNotNull(builder.build());
        }

        @Test
        @DisplayName("Should build transport wrapper for default transport failure")
        void testBuildTransportWrapperForDefaultTransportFailure() throws Exception {
            AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
            assertThrows(IllegalArgumentException.class, builder::build);
        }
    }

    @SuppressWarnings("rawtypes")
    public static class MockTransportWrapperBuilder
            implements TransportWrapperBuilder<TransportWrapper> {

        @Override
        public String getTransportType() {
            return "MOCK_SUCCESS";
        }

        @Override
        public TransportWrapper build(
                AgentCard agentCard,
                RequestHandler requestHandler,
                Executor executor,
                AgentCard extendedAgentCard) {
            return mock(TransportWrapper.class);
        }
    }

    @SuppressWarnings("rawtypes")
    public static class DuplicateTransportWrapperBuilder
            implements TransportWrapperBuilder<TransportWrapper> {

        @Override
        public String getTransportType() {
            return "MOCK_SUCCESS";
        }

        @Override
        public TransportWrapper build(
                AgentCard agentCard,
                RequestHandler requestHandler,
                Executor executor,
                AgentCard extendedAgentCard) {
            return mock(TransportWrapper.class);
        }
    }

    @SuppressWarnings("rawtypes")
    public static class ExceptionTransportWrapperBuilder
            implements TransportWrapperBuilder<TransportWrapper> {

        @Override
        public String getTransportType() {
            return "MOCK_EXCEPTION";
        }

        @Override
        public TransportWrapper build(
                AgentCard agentCard,
                RequestHandler requestHandler,
                Executor executor,
                AgentCard extendedAgentCard) {
            throw new RuntimeException("mock exception");
        }
    }
}
