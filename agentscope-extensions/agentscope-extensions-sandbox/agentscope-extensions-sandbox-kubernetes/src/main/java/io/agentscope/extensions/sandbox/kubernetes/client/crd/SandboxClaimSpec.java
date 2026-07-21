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
import java.util.Map;

/** Spec for {@link SandboxClaim}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxClaimSpec {

    @JsonProperty("warmPoolRef")
    private WarmPoolRef warmPoolRef;

    @JsonProperty("lifecycle")
    private Lifecycle lifecycle;

    @JsonProperty("additionalPodMetadata")
    private AdditionalPodMetadata additionalPodMetadata;

    public SandboxClaimSpec() {}

    public SandboxClaimSpec(String warmPoolName) {
        this.warmPoolRef = new WarmPoolRef(warmPoolName);
    }

    public WarmPoolRef getWarmPoolRef() {
        return warmPoolRef;
    }

    public void setWarmPoolRef(WarmPoolRef warmPoolRef) {
        this.warmPoolRef = warmPoolRef;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public AdditionalPodMetadata getAdditionalPodMetadata() {
        return additionalPodMetadata;
    }

    public void setAdditionalPodMetadata(AdditionalPodMetadata additionalPodMetadata) {
        this.additionalPodMetadata = additionalPodMetadata;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WarmPoolRef {
        @JsonProperty("name")
        private String name;

        public WarmPoolRef() {}

        public WarmPoolRef(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Lifecycle {
        @JsonProperty("shutdownTime")
        private String shutdownTime;

        @JsonProperty("shutdownPolicy")
        private String shutdownPolicy;

        public Lifecycle() {}

        public Lifecycle(String shutdownTime, String shutdownPolicy) {
            this.shutdownTime = shutdownTime;
            this.shutdownPolicy = shutdownPolicy;
        }

        public String getShutdownTime() {
            return shutdownTime;
        }

        public void setShutdownTime(String shutdownTime) {
            this.shutdownTime = shutdownTime;
        }

        public String getShutdownPolicy() {
            return shutdownPolicy;
        }

        public void setShutdownPolicy(String shutdownPolicy) {
            this.shutdownPolicy = shutdownPolicy;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AdditionalPodMetadata {
        @JsonProperty("labels")
        private Map<String, String> labels;

        @JsonProperty("annotations")
        private Map<String, String> annotations;

        public AdditionalPodMetadata() {}

        public AdditionalPodMetadata(Map<String, String> labels, Map<String, String> annotations) {
            this.labels = labels;
            this.annotations = annotations;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }

        public Map<String, String> getAnnotations() {
            return annotations;
        }

        public void setAnnotations(Map<String, String> annotations) {
            this.annotations = annotations;
        }
    }
}
