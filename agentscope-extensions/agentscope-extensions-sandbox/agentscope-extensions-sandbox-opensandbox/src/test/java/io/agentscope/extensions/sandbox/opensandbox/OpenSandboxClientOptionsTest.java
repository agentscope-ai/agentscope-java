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
package io.agentscope.extensions.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSandboxClientOptionsTest {

    @Test
    void endpointWithoutSchemeDefaultsToHttp() {
        OpenSandboxEndpoint endpoint = OpenSandboxEndpoint.parse("sandbox.example.com:8090");

        assertEquals("http", endpoint.protocol());
        assertEquals("sandbox.example.com:8090", endpoint.domain());
    }

    @Test
    void endpointWithHttpsSeparatesProtocolAndDomain() {
        OpenSandboxEndpoint endpoint = OpenSandboxEndpoint.parse("https://sandbox.example.com/");

        assertEquals("https", endpoint.protocol());
        assertEquals("sandbox.example.com", endpoint.domain());
    }

    @Test
    void endpointRejectsPathAndUnsupportedScheme() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenSandboxEndpoint.parse("http://localhost:8080/v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenSandboxEndpoint.parse("ftp://localhost:8080"));
    }

    @Test
    void defaultsMatchOpenSandboxRuntimeContract() {
        OpenSandboxClientOptions options = new OpenSandboxClientOptions();

        assertEquals("opensandbox", options.getType());
        assertEquals("http://localhost:8080", options.getEndpoint());
        assertEquals("ubuntu:22.04", options.getImage());
        assertEquals(List.of("tail", "-f", "/dev/null"), options.getEntrypoint());
        assertEquals(Map.of("cpu", "1", "memory", "2Gi"), options.getResourceLimits());
        assertEquals(600, options.getSandboxTimeoutSeconds());
        assertEquals(30, options.getReadyTimeoutSeconds());
        assertEquals(30, options.getRequestTimeoutSeconds());
    }

    @Test
    void invalidValuesAreRejected() {
        OpenSandboxClientOptions options = new OpenSandboxClientOptions();

        assertThrows(IllegalArgumentException.class, () -> options.setReadyTimeoutSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> options.setRequestTimeoutSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> options.setSandboxTimeoutSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> options.setImage(" "));
        assertThrows(IllegalArgumentException.class, () -> options.setEntrypoint(List.of()));
    }

    @Test
    void createClientReturnsOpenSandboxClient() {
        assertInstanceOf(OpenSandboxClient.class, new OpenSandboxClientOptions().createClient());
    }
}
