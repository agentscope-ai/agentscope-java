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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ManagedMqCredentialsTest {
    @Test
    void unauthenticatedLocalBrokerRemainsSupported() {
        assertNull(ManagedInfrastructureConfiguration.mqCredentials("", ""));
        assertNull(ManagedInfrastructureConfiguration.mqCredentials(null, null));
    }

    @Test
    void partialCredentialsFailWithoutDisclosingValues() {
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ManagedInfrastructureConfiguration.mqCredentials("test-access", ""));
        assertEquals(
                "RocketMQ access-key and secret-key must be configured together",
                failure.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> ManagedInfrastructureConfiguration.mqCredentials(" ", "test-secret"));
    }

    @Test
    void completeCredentialsReachTheSdkUnchanged() {
        var credentials =
                ManagedInfrastructureConfiguration.mqCredentials("test-access", "test-secret")
                        .getSessionCredentials();
        assertEquals("test-access", credentials.getAccessKey());
        assertEquals("test-secret", credentials.getAccessSecret());
    }
}
