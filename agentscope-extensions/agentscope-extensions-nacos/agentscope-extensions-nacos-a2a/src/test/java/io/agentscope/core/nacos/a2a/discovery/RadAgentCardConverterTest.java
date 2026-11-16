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

package io.agentscope.core.nacos.a2a.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alibaba.nacos.api.ai.model.agent.AgentCallInterface;
import com.alibaba.nacos.api.ai.model.agent.Endpoint;
import com.alibaba.nacos.api.ai.model.agent.EndpointSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests A2A descriptor preservation and RAD endpoint projection boundaries. */
class RadAgentCardConverterTest {
    @Test
    void missingAndNonA2aSnapshotsReportConversionFailure() {
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(null));
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        result.getCallInterfaces().get(0).setProtocol("other");
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
        result.setCallInterfaces(List.of());
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
        result.setCallInterfaces(null);
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
    }

    @Test
    void absentEndpointCollectionsReportConversionFailure() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        var call = result.getCallInterfaces().get(0);
        for (EndpointSet set : call.getEndpointSets()) set.setEndpoints(null);
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
        call.setEndpointSets(null);
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
    }

    @Test
    void selectsA2aDescriptorAmongOtherProtocols() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        AgentCallInterface other = new AgentCallInterface();
        other.setProtocol("other");
        other.setDescriptorMediaType("application/json");
        other.setEndpointSets(List.of());
        other.setNativeDescriptor(Map.of("unrelated", true));
        result.setCallInterfaces(List.of(other, result.getCallInterfaces().get(0)));
        assertEquals("demo", RadAgentCardConverter.convert(result).name());
    }

    @Test
    void missingAndMismatchedDescriptorsReportConversionFailure() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        result.setVersion("2.0.0");
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
        result.setVersion("1.0.0");
        result.setAgentName("another");
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
        result.getCallInterfaces().get(0).setNativeDescriptor(null);
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
    }

    @Test
    void malformedDescriptorReportsConversionFailure() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        result.getCallInterfaces().get(0).setNativeDescriptor(List.of("not a Card"));
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
    }

    @Test
    void emptyRuntimeFallsBackToDeclaredWithoutRestoringFilteredAddresses() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        List<EndpointSet> sets = result.getCallInterfaces().get(0).getEndpointSets();
        sets.get(0).setEndpoints(List.of());
        Endpoint fallback = new Endpoint();
        fallback.setUri("http://declared:80/agent");
        fallback.setTransport("JSONRPC");
        fallback.setHealthy(false);
        sets.get(1).setEndpoints(List.of(fallback));
        assertEquals(
                "http://declared:80/agent",
                RadAgentCardConverter.convert(result).supportedInterfaces().get(0).url());
        sets.get(1).setEndpoints(List.of());
        assertThrows(IllegalArgumentException.class, () -> RadAgentCardConverter.convert(result));
    }

    @Test
    void respectsDeclaredSourcePreferenceAndKeepsDescriptorUntouched() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        var call = result.getCallInterfaces().get(0);
        var sets = call.getEndpointSets();
        Endpoint declared = new Endpoint();
        declared.setUri("http://preferred-declared:80/agent");
        declared.setTransport("JSONRPC");
        sets.get(1).setEndpoints(List.of(declared));
        call.setEndpointSets(List.of(sets.get(1), sets.get(0)));
        assertEquals(
                "http://preferred-declared:80/agent",
                RadAgentCardConverter.convert(result).supportedInterfaces().get(0).url());
        assertEquals("http://declared/agent", ((Map<?, ?>) call.getNativeDescriptor()).get("url"));
    }

    @Test
    void preservesSdkEndpointOrderInsteadOfDescriptorTransportPreference() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        var runtime = result.getCallInterfaces().get(0).getEndpointSets().get(0);
        Endpoint grpc = new Endpoint();
        grpc.setUri("http://grpc:9000/");
        grpc.setPriority(0);
        grpc.setBindings(runtime.getEndpoints().get(0).getBindings());
        runtime.getEndpoints().get(0).setPriority(1);
        grpc.setTransport("GRPC");
        runtime.setEndpoints(List.of(grpc, runtime.getEndpoints().get(0)));
        var card = RadAgentCardConverter.convert(result);
        assertEquals("http://grpc:9000/", card.supportedInterfaces().get(0).url());
        assertEquals("GRPC", card.supportedInterfaces().get(0).protocolBinding());
        assertEquals(2, card.supportedInterfaces().size());
        assertEquals("GRPC", card.supportedInterfaces().get(0).protocolBinding());
    }

    @Test
    void absentOptionalExtendedCardFlagDefaultsToFalse() {
        var result = NacosAgentCardResolverModeTest.snapshot("1.0.0");
        ((Map<?, ?>) result.getCallInterfaces().get(0).getNativeDescriptor())
                .remove("supportsAuthenticatedExtendedCard");
        assertFalse(RadAgentCardConverter.convert(result).capabilities().extendedAgentCard());
    }
}
