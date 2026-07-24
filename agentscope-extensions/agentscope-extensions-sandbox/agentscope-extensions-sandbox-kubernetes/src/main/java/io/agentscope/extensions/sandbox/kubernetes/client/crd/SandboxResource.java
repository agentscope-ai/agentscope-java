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
package io.agentscope.extensions.sandbox.kubernetes.client.crd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;
import java.util.List;
import java.util.Map;

/**
 * Minimal Fabric8 CRD binding for {@code Sandbox}
 * (agents.x-k8s.io/v1beta1).
 */
@Group("agents.x-k8s.io")
@Version("v1beta1")
@Kind("Sandbox")
@Plural("sandboxes")
public class SandboxResource extends CustomResource<Void, SandboxResource.SandboxStatusData>
        implements Namespaced {

    @Override
    protected SandboxStatusData initStatus() {
        return new SandboxStatusData();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SandboxStatusData {

        @JsonProperty("conditions")
        private List<SandboxClaimStatus.Condition> conditions;

        @JsonProperty("podIPs")
        private List<String> podIPs;

        @JsonProperty("selector")
        private String selector;

        public List<SandboxClaimStatus.Condition> getConditions() {
            return conditions;
        }

        public void setConditions(List<SandboxClaimStatus.Condition> conditions) {
            this.conditions = conditions;
        }

        public List<String> getPodIPs() {
            return podIPs;
        }

        public void setPodIPs(List<String> podIPs) {
            this.podIPs = podIPs;
        }

        public String getSelector() {
            return selector;
        }

        public void setSelector(String selector) {
            this.selector = selector;
        }
    }

    /** Resolved identity metadata extracted from a ready Sandbox resource. */
    public static class SandboxIdentity {
        private final String sandboxName;
        private final String podName;
        private final String podIP;
        private final Map<String, String> annotations;

        public SandboxIdentity(
                String sandboxName, String podName, String podIP, Map<String, String> annotations) {
            this.sandboxName = sandboxName;
            this.podName = podName;
            this.podIP = podIP;
            this.annotations = annotations;
        }

        public String getSandboxName() {
            return sandboxName;
        }

        public String getPodName() {
            return podName;
        }

        public String getPodIP() {
            return podIP;
        }

        public Map<String, String> getAnnotations() {
            return annotations;
        }
    }
}
