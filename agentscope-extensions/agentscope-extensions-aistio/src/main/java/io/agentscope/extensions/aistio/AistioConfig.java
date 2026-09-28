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
 * <p>Standalone BYO (recommended locally): set {@code controlPlaneHttp} + {@code internalToken},
 * keep {@code startGrpc=false}. The bridge serves {@code /agentscope/*} and self-registers via
 * {@code POST /api/v1/agent-registrations}.
 *
 * <p>Optional ASDP gRPC: set {@code controlPlane} ({@code host:port}) and {@code startGrpc=true}.
 *
 * @param controlPlane aistiod ASDP gRPC endpoint, {@code host:port} (optional)
 * @param controlPlaneHttp aistiod REST base URL for HTTP self-register, e.g. {@code
 *     http://localhost:8081}
 * @param internalToken trusted bootstrap/workload token for first registration
 * @param registrationCredential Agent registration credential for an existing logical identity
 * @param agentId stable Catalog Agent UUID when registration is performed out of band
 * @param bindingId stable external Binding UUID when registration is performed out of band
 * @param generation registered AgentInstance generation when registration is performed out of band
 * @param agentKey stable logical key registered in the control plane
 * @param tenant collaboration tenant
 * @param namespace collaboration / Kubernetes namespace
 * @param instanceKey this replica's stable key; defaults to {@code HOSTNAME} then the local host name
 * @param enableEvents whether to persist and push the Level-2 event stream (on by default when
 *     ASDP gRPC is enabled because it is the canonical conversation history used for reconnect
 *     and replay)
 * @param eventJournalDir directory for the durable event outbox; empty uses {@code
 *     ~/.agentscope/aistio/event-journal}
 * @param contractHttpPort port for the in-process {@code /agentscope/*} contract server; {@code 0}
 *     binds an ephemeral port
 * @param contractHttpHost bind address, empty for all interfaces
 * @param publicBaseUrl URL the control plane should use to reach the contract server; empty derives
 *     {@code http://localhost:<boundPort>}
 * @param sessionAffinity affinity hint the control plane uses when routing session commands
 * @param startHttp whether to start the contract server
 * @param startGrpc whether to open the ASDP upstream channel
 * @param startHttpRegister whether to POST /api/v1/agent-registrations
 */
public record AistioConfig(
        String controlPlane,
        String controlPlaneHttp,
        String internalToken,
        String registrationCredential,
        String agentId,
        String bindingId,
        long generation,
        String agentKey,
        String tenant,
        String namespace,
        String instanceKey,
        boolean enableEvents,
        String eventJournalDir,
        int contractHttpPort,
        String contractHttpHost,
        String publicBaseUrl,
        String sessionAffinity,
        boolean startHttp,
        boolean startGrpc,
        boolean startHttpRegister) {

    public AistioConfig {
        if (agentKey == null || agentKey.isBlank()) {
            throw new IllegalArgumentException("agentKey is required");
        }
        tenant = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        instanceKey =
                (instanceKey == null || instanceKey.isBlank()) ? defaultInstanceId() : instanceKey;
        controlPlane = controlPlane == null ? "" : controlPlane;
        controlPlaneHttp = controlPlaneHttp == null ? "" : controlPlaneHttp.trim();
        internalToken = internalToken == null ? "" : internalToken;
        registrationCredential =
                registrationCredential == null ? "" : registrationCredential.trim();
        agentId = agentId == null ? "" : agentId.trim();
        bindingId = bindingId == null ? "" : bindingId.trim();
        eventJournalDir = eventJournalDir == null ? "" : eventJournalDir.trim();
        contractHttpHost = contractHttpHost == null ? "" : contractHttpHost;
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
        if (startGrpc && controlPlane.isBlank()) {
            throw new IllegalArgumentException("controlPlane is required when gRPC is enabled");
        }
        if (startGrpc
                && !startHttpRegister
                && (agentId.isBlank() || bindingId.isBlank() || generation <= 0)) {
            throw new IllegalArgumentException(
                    "agentId, bindingId, and generation are required for gRPC without"
                            + " registration");
        }
        if (startHttpRegister && controlPlaneHttp.isBlank()) {
            throw new IllegalArgumentException(
                    "controlPlaneHttp is required when HTTP self-register is enabled");
        }
    }

    public static Builder builder(String agentKey) {
        return new Builder(agentKey);
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
        private final String agentKey;
        private String controlPlane = "";
        private String controlPlaneHttp = "";
        private String internalToken = "";
        private String registrationCredential = "";
        private String agentId = "";
        private String bindingId = "";
        private long generation;
        private String tenant = "default";
        private String namespace = "default";
        private String instanceKey = "";
        private Boolean enableEvents;
        private String eventJournalDir = "";
        private int contractHttpPort = 18090;
        private String contractHttpHost = "";
        private String publicBaseUrl = "";
        private String sessionAffinity = "";
        private boolean startHttp = true;
        private boolean startGrpc = false;
        private Boolean startHttpRegister;

        private Builder(String agentKey) {
            this.agentKey = agentKey;
        }

        public Builder controlPlane(String controlPlane) {
            this.controlPlane = controlPlane;
            return this;
        }

        /** aistiod REST base URL used for {@code /api/v1/dataplanes/*} self-registration. */
        public Builder controlPlaneHttp(String controlPlaneHttp) {
            this.controlPlaneHttp = controlPlaneHttp;
            return this;
        }

        public Builder internalToken(String internalToken) {
            this.internalToken = internalToken;
            return this;
        }

        public Builder registrationCredential(String registrationCredential) {
            this.registrationCredential = registrationCredential;
            return this;
        }

        public Builder registeredIdentity(String agentId, String bindingId, long generation) {
            this.agentId = agentId;
            this.bindingId = bindingId;
            this.generation = generation;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder instanceKey(String instanceKey) {
            this.instanceKey = instanceKey;
            return this;
        }

        public Builder enableEvents(boolean enableEvents) {
            this.enableEvents = enableEvents;
            return this;
        }

        /** Directory used by the durable, acknowledged Level-2 event outbox. */
        public Builder eventJournalDir(String eventJournalDir) {
            this.eventJournalDir = eventJournalDir;
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

        public Builder publicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
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

        public Builder startHttpRegister(boolean startHttpRegister) {
            this.startHttpRegister = startHttpRegister;
            return this;
        }

        public AistioConfig build() {
            boolean register =
                    startHttpRegister != null
                            ? startHttpRegister
                            : controlPlaneHttp != null && !controlPlaneHttp.isBlank();
            return new AistioConfig(
                    controlPlane,
                    controlPlaneHttp,
                    internalToken,
                    registrationCredential,
                    agentId,
                    bindingId,
                    generation,
                    agentKey,
                    tenant,
                    namespace,
                    instanceKey,
                    enableEvents != null ? enableEvents : startGrpc,
                    eventJournalDir,
                    contractHttpPort,
                    contractHttpHost,
                    publicBaseUrl,
                    sessionAffinity,
                    startHttp,
                    startGrpc,
                    register);
        }
    }
}
