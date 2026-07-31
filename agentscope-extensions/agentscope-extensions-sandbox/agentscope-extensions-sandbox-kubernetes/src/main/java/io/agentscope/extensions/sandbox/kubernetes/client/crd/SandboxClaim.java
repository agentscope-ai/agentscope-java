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

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Fabric8 CRD binding for {@code SandboxClaim}
 * (extensions.agents.x-k8s.io/v1beta1).
 */
@Group("extensions.agents.x-k8s.io")
@Version("v1beta1")
@Kind("SandboxClaim")
@Plural("sandboxclaims")
public class SandboxClaim extends CustomResource<SandboxClaimSpec, SandboxClaimStatus>
        implements Namespaced {

    @Override
    protected SandboxClaimSpec initSpec() {
        return new SandboxClaimSpec();
    }

    @Override
    protected SandboxClaimStatus initStatus() {
        return new SandboxClaimStatus();
    }
}
