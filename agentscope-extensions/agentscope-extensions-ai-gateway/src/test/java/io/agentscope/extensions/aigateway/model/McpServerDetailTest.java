/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.extensions.aigateway.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpServerDetailTest {

    @Test
    void testDefaultValues() {
        McpServerDetail detail = new McpServerDetail();

        assertNull(detail.getMcpServerId());
        assertNull(detail.getName());
        assertNull(detail.getDescription());
        assertNull(detail.getType());
        assertNull(detail.getProtocol());
        assertNull(detail.getMcpServerPath());
        assertNull(detail.getExposedUriPath());
        assertNull(detail.getGatewayId());
        assertNull(detail.getEnvironmentId());
        assertNull(detail.getRouteId());
        assertNull(detail.getDeployStatus());
        assertNull(detail.getCreateFromType());
        assertNull(detail.getDomainIds());
        assertNull(detail.getDomainInfos());
        assertNull(detail.getBackend());
        assertNull(detail.getAssembledSources());
    }

    @Test
    void testSettersAndGetters() {
        McpServerDetail detail = new McpServerDetail();

        detail.setMcpServerId("mcp-123");
        detail.setName("test-mcp-server");
        detail.setDescription("Test MCP Server");
        detail.setType("RealMCP");
        detail.setProtocol("HTTP");
        detail.setMcpServerPath("/mcp-servers/test");
        detail.setExposedUriPath("/sse");
        detail.setGatewayId("gw-456");
        detail.setEnvironmentId("env-789");
        detail.setRouteId("route-abc");
        detail.setDeployStatus("Deployed");
        detail.setCreateFromType("ApiGatewayHttpToMCP");
        detail.setDomainIds(Arrays.asList("domain-1", "domain-2"));

        assertEquals("mcp-123", detail.getMcpServerId());
        assertEquals("test-mcp-server", detail.getName());
        assertEquals("Test MCP Server", detail.getDescription());
        assertEquals("RealMCP", detail.getType());
        assertEquals("HTTP", detail.getProtocol());
        assertEquals("/mcp-servers/test", detail.getMcpServerPath());
        assertEquals("/sse", detail.getExposedUriPath());
        assertEquals("gw-456", detail.getGatewayId());
        assertEquals("env-789", detail.getEnvironmentId());
        assertEquals("route-abc", detail.getRouteId());
        assertEquals("Deployed", detail.getDeployStatus());
        assertEquals("ApiGatewayHttpToMCP", detail.getCreateFromType());
        assertEquals(2, detail.getDomainIds().size());
    }

    @Test
    void testDomainInfo() {
        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();

        domainInfo.setDomainId("d-123");
        domainInfo.setName("www.example.com");
        domainInfo.setProtocol("HTTPS");

        assertEquals("d-123", domainInfo.getDomainId());
        assertEquals("www.example.com", domainInfo.getName());
        assertEquals("HTTPS", domainInfo.getProtocol());
    }

    @Test
    void testBackend() {
        McpServerDetail.Backend backend = new McpServerDetail.Backend();

        backend.setScene("Single");
        assertNull(backend.getServices());

        List<McpServerDetail.Service> services = new ArrayList<>();
        backend.setServices(services);

        assertEquals("Single", backend.getScene());
        assertNotNull(backend.getServices());
    }

    @Test
    void testService() {
        McpServerDetail.Service service = new McpServerDetail.Service();

        service.setServiceId("svc-123");
        service.setName("test-service");
        service.setProtocol("HTTP");
        service.setPort(8080);
        service.setWeight(100);
        service.setVersion("v1");

        assertEquals("svc-123", service.getServiceId());
        assertEquals("test-service", service.getName());
        assertEquals("HTTP", service.getProtocol());
        assertEquals(8080, service.getPort());
        assertEquals(100, service.getWeight());
        assertEquals("v1", service.getVersion());
    }

    @Test
    void testAssembledSource() {
        McpServerDetail.AssembledSource source = new McpServerDetail.AssembledSource();

        source.setMcpServerId("mcp-source-1");
        source.setMcpServerName("source-server");
        source.setTools(Arrays.asList("tool1", "tool2", "tool3"));

        assertEquals("mcp-source-1", source.getMcpServerId());
        assertEquals("source-server", source.getMcpServerName());
        assertEquals(3, source.getTools().size());
        assertTrue(source.getTools().contains("tool1"));
    }

    @Test
    void testGetSseEndpointUrlWithHttpProtocol() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerPath("/mcp-servers/test");
        detail.setExposedUriPath("/sse");

        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setName("api.example.com");
        domainInfo.setProtocol("HTTP");

        detail.setDomainInfos(Arrays.asList(domainInfo));

        String url = detail.getSseEndpointUrl();
        assertEquals("http://api.example.com/mcp-servers/test/sse", url);
    }

    @Test
    void testGetSseEndpointUrlWithHttpsProtocol() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerPath("/mcp-servers/test");
        detail.setExposedUriPath("/events");

        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setName("secure.example.com");
        domainInfo.setProtocol("HTTPS");

        detail.setDomainInfos(Arrays.asList(domainInfo));

        String url = detail.getSseEndpointUrl();
        assertEquals("https://secure.example.com/mcp-servers/test/events", url);
    }

    @Test
    void testGetSseEndpointUrlWithNullDomainInfos() {
        McpServerDetail detail = new McpServerDetail();
        detail.setDomainInfos(null);

        assertNull(detail.getSseEndpointUrl());
    }

    @Test
    void testGetSseEndpointUrlWithEmptyDomainInfos() {
        McpServerDetail detail = new McpServerDetail();
        detail.setDomainInfos(new ArrayList<>());

        assertNull(detail.getSseEndpointUrl());
    }

    @Test
    void testGetSseEndpointUrlWithNullPaths() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerPath(null);
        detail.setExposedUriPath(null);

        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setName("api.example.com");
        domainInfo.setProtocol("HTTP");

        detail.setDomainInfos(Arrays.asList(domainInfo));

        String url = detail.getSseEndpointUrl();
        assertEquals("http://api.example.com/sse", url);
    }

    @Test
    void testGetHttpEndpointUrl() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerPath("/mcp-servers/test");

        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setName("api.example.com");
        domainInfo.setProtocol("HTTPS");

        detail.setDomainInfos(Arrays.asList(domainInfo));

        String url = detail.getHttpEndpointUrl();
        assertEquals("https://api.example.com/mcp-servers/test", url);
    }

    @Test
    void testGetHttpEndpointUrlWithNullDomainInfos() {
        McpServerDetail detail = new McpServerDetail();

        assertNull(detail.getHttpEndpointUrl());
    }

    @Test
    void testGetHttpEndpointUrlWithEmptyDomainInfos() {
        McpServerDetail detail = new McpServerDetail();
        detail.setDomainInfos(new ArrayList<>());

        assertNull(detail.getHttpEndpointUrl());
    }

    @Test
    void testGetHttpEndpointUrlWithNullPath() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerPath(null);

        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setName("api.example.com");
        domainInfo.setProtocol("HTTP");

        detail.setDomainInfos(Arrays.asList(domainInfo));

        String url = detail.getHttpEndpointUrl();
        assertEquals("http://api.example.com", url);
    }

    @Test
    void testToString() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerId("mcp-123");
        detail.setName("test-server");
        detail.setDescription("Test Description");
        detail.setType("RealMCP");
        detail.setDeployStatus("Deployed");

        String str = detail.toString();

        assertNotNull(str);
        assertTrue(str.contains("mcp-123"));
        assertTrue(str.contains("test-server"));
        assertTrue(str.contains("Test Description"));
        assertTrue(str.contains("RealMCP"));
        assertTrue(str.contains("Deployed"));
    }

    @Test
    void testCompleteServerSetup() {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerId("mcp-complete");
        detail.setName("complete-server");
        detail.setProtocol("HTTP");
        detail.setMcpServerPath("/mcp-servers/complete");
        detail.setExposedUriPath("/sse");

        // Setup domain info
        McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
        domainInfo.setDomainId("d-1");
        domainInfo.setName("complete.example.com");
        domainInfo.setProtocol("HTTPS");
        detail.setDomainInfos(Arrays.asList(domainInfo));

        // Setup backend
        McpServerDetail.Service service = new McpServerDetail.Service();
        service.setServiceId("svc-1");
        service.setName("backend-service");
        service.setPort(8080);

        McpServerDetail.Backend backend = new McpServerDetail.Backend();
        backend.setScene("Single");
        backend.setServices(Arrays.asList(service));
        detail.setBackend(backend);

        // Setup assembled sources
        McpServerDetail.AssembledSource source = new McpServerDetail.AssembledSource();
        source.setMcpServerId("mcp-sub");
        source.setMcpServerName("sub-server");
        source.setTools(Arrays.asList("tool1"));
        detail.setAssembledSources(Arrays.asList(source));

        // Verify everything is set correctly
        assertNotNull(detail.getSseEndpointUrl());
        assertNotNull(detail.getHttpEndpointUrl());
        assertEquals("Single", detail.getBackend().getScene());
        assertEquals(1, detail.getBackend().getServices().size());
        assertEquals(1, detail.getAssembledSources().size());
    }
}
