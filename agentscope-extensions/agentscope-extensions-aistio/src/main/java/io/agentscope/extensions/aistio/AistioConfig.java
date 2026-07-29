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
package io.agentscope.extensions.aistio;

/**
 * Connection and reporting settings for a {@link SessionBridge}.
 *
 * @param controlPlane aistiod ASDP endpoint, {@code host:port}
 * @param agentName logical agent name registered in the control plane
 * @param namespace tenant / Kubernetes namespace
 * @param instanceId this replica's identity; defaults to {@code HOSTNAME} then the local host name
 * @param enableEvents whether to push the Level-2 event stream (off by default: it is the only
 *     level whose volume scales with conversation traffic)
 * @param contractHttpPort port for the in-process {@code /agentscope/*} contract server; {@code 0}
 *     binds an ephemeral port
 * @param contractHttpHost bind address, empty for all interfaces
 * @param sessionAffinity affinity hint the control plane uses when routing session commands
 * @param startHttp whether to start the contract server
 * @param startGrpc whether to open the ASDP upstream channel
 */
public record AistioConfig(
        String controlPlane,
        String agentName,
        String namespace,
        String instanceId,
        boolean enableEvents,
        int contractHttpPort,
        String contractHttpHost,
        String sessionAffinity,
        boolean startHttp,
        boolean startGrpc) {

    public AistioConfig {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName is required");
        }
        namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        instanceId =
                (instanceId == null || instanceId.isBlank()) ? defaultInstanceId() : instanceId;
        controlPlane = controlPlane == null ? "" : controlPlane;
        contractHttpHost = contractHttpHost == null ? "" : contractHttpHost;
        sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
        if (startGrpc && controlPlane.isBlank()) {
            throw new IllegalArgumentException("controlPlane is required when gRPC is enabled");
        }
    }

    public static Builder builder(String agentName) {
        return new Builder(agentName);
    }

    private static String defaultInstanceId() {
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }

    /** Mutable builder for {@link AistioConfig}. */
    public static final class Builder {
        private final String agentName;
        private String controlPlane = "";
        private String namespace = "default";
        private String instanceId = "";
        private boolean enableEvents;
        private int contractHttpPort = 8080;
        private String contractHttpHost = "";
        private String sessionAffinity = "";
        private boolean startHttp = true;
        private boolean startGrpc = true;

        private Builder(String agentName) {
            this.agentName = agentName;
        }

        public Builder controlPlane(String controlPlane) {
            this.controlPlane = controlPlane;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder enableEvents(boolean enableEvents) {
            this.enableEvents = enableEvents;
            return this;
        }

        public Builder contractHttpPort(int contractHttpPort) {
            this.contractHttpPort = contractHttpPort;
            return this;
        }

        public Builder contractHttpHost(String contractHttpHost) {
            this.contractHttpHost = contractHttpHost;
            return this;
        }

        public Builder sessionAffinity(String sessionAffinity) {
            this.sessionAffinity = sessionAffinity;
            return this;
        }

        public Builder startHttp(boolean startHttp) {
            this.startHttp = startHttp;
            return this;
        }

        public Builder startGrpc(boolean startGrpc) {
            this.startGrpc = startGrpc;
            return this;
        }

        public AistioConfig build() {
            return new AistioConfig(
                    controlPlane,
                    agentName,
                    namespace,
                    instanceId,
                    enableEvents,
                    contractHttpPort,
                    contractHttpHost,
                    sessionAffinity,
                    startHttp,
                    startGrpc);
        }
    }
}
