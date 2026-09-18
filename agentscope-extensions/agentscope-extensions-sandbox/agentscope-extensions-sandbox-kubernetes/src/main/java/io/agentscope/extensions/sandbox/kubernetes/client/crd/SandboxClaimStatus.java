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
import java.util.List;

/** Status for {@link SandboxClaim}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxClaimStatus {

    @JsonProperty("sandbox")
    private ClaimSandboxStatus sandbox;

    @JsonProperty("conditions")
    private List<Condition> conditions;

    public ClaimSandboxStatus getSandbox() {
        return sandbox;
    }

    public void setSandbox(ClaimSandboxStatus sandbox) {
        this.sandbox = sandbox;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaimSandboxStatus {
        @JsonProperty("name")
        private String name;

        @JsonProperty("podIPs")
        private List<String> podIPs;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getPodIPs() {
            return podIPs;
        }

        public void setPodIPs(List<String> podIPs) {
            this.podIPs = podIPs;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Condition {
        @JsonProperty("type")
        private String type;

        @JsonProperty("status")
        private String status;

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("message")
        private String message;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
