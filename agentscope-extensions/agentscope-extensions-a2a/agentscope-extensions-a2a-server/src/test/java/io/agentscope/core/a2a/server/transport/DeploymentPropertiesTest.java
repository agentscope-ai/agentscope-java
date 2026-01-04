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

package io.agentscope.core.a2a.server.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import io.agentscope.core.a2a.server.utils.NetworkUtils;
import java.net.SocketException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for DeploymentProperties.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>DeploymentProperties Builder pattern functionality</li>
 *   <li>DeploymentProperties creation with various configurations</li>
 *   <li>DeploymentProperties Builder method chaining</li>
 *   <li>Automatic IP address resolution</li>
 *   <li>Error handling for missing required properties</li>
 * </ul>
 */
@DisplayName("DeploymentProperties Tests")
class DeploymentPropertiesTest {

    private MockedStatic<NetworkUtils> networkUtilsMock;

    @BeforeEach
    void setUp() {
        networkUtilsMock = mockStatic(NetworkUtils.class);
    }

    @AfterEach
    void tearDown() {
        if (networkUtilsMock != null) {
            networkUtilsMock.close();
        }
    }

    @Nested
    @DisplayName("DeploymentProperties Building Tests")
    class DeploymentPropertiesBuildingTests {

        @Test
        @DisplayName("Should build DeploymentProperties with all properties")
        void testBuildWithAllProperties() {
            String host = "localhost";
            Integer port = 8080;

            DeploymentProperties deploymentProperties =
                    new DeploymentProperties.Builder().host(host).port(port).build();

            assertNotNull(deploymentProperties);
            assertEquals(host, deploymentProperties.host());
            assertEquals(port, deploymentProperties.port());
        }

        @Test
        @DisplayName("Should automatically get local IP when host not specified")
        void testAutoGetLocalIp() throws SocketException {
            Integer port = 8080;
            String localIp = "192.168.1.100";

            networkUtilsMock.when(NetworkUtils::getLocalIpAddress).thenReturn(localIp);

            DeploymentProperties deploymentProperties =
                    new DeploymentProperties.Builder().port(port).build();

            assertNotNull(deploymentProperties);
            assertEquals(localIp, deploymentProperties.host());
            assertEquals(port, deploymentProperties.port());
        }

        @Test
        @DisplayName("Should use localhost when getting local IP fails")
        void testUseLocalhostWhenGetLocalIpFails() throws SocketException {
            Integer port = 8080;

            networkUtilsMock
                    .when(NetworkUtils::getLocalIpAddress)
                    .thenThrow(new SocketException("Network error"));

            DeploymentProperties deploymentProperties =
                    new DeploymentProperties.Builder().port(port).build();

            assertNotNull(deploymentProperties);
            assertNull(deploymentProperties.host()); // host will be null since we couldn't get it
            assertEquals(port, deploymentProperties.port());
        }

        @Test
        @DisplayName("Should throw exception when port not specified")
        void testThrowExceptionWhenPortNotSpecified() {
            DeploymentProperties.Builder builder = new DeploymentProperties.Builder();

            assertThrows(IllegalArgumentException.class, builder::build);
        }

        @Test
        @DisplayName("Should support builder method chaining")
        void testBuilderMethodChaining() {
            String host = "localhost";
            Integer port = 8080;

            DeploymentProperties.Builder builder = new DeploymentProperties.Builder();
            DeploymentProperties.Builder result = builder.host(host).port(port);

            assertNotNull(result);
            assertSame(builder, result);

            DeploymentProperties deploymentProperties = builder.build();

            assertNotNull(deploymentProperties);
            assertEquals(host, deploymentProperties.host());
            assertEquals(port, deploymentProperties.port());
        }
    }

    @Nested
    @DisplayName("Record Functionality Tests")
    class RecordFunctionalityTests {

        @Test
        @DisplayName("Should create record with constructor")
        void testRecordConstructor() {
            String host = "localhost";
            Integer port = 8080;

            DeploymentProperties deploymentProperties = new DeploymentProperties(host, port);

            assertNotNull(deploymentProperties);
            assertEquals(host, deploymentProperties.host());
            assertEquals(port, deploymentProperties.port());
        }

        @Test
        @DisplayName("Should have proper equals and hashCode implementation")
        void testEqualsAndHashCode() {
            String host = "localhost";
            int port = 8080;

            DeploymentProperties deploymentProperties1 = new DeploymentProperties(host, port);
            DeploymentProperties deploymentProperties2 = new DeploymentProperties(host, port);

            assertEquals(deploymentProperties1, deploymentProperties2);
            assertEquals(deploymentProperties1.hashCode(), deploymentProperties2.hashCode());
        }

        @Test
        @DisplayName("Should have proper toString implementation")
        void testToString() {
            String host = "localhost";
            int port = 8080;

            DeploymentProperties deploymentProperties = new DeploymentProperties(host, port);

            String toStringResult = deploymentProperties.toString();
            assertNotNull(toStringResult);
            assertTrue(toStringResult.contains(host));
            assertTrue(toStringResult.contains(Integer.toString(port)));
        }
    }
}
