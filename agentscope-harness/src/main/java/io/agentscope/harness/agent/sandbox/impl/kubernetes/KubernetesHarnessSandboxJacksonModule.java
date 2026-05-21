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
package io.agentscope.harness.agent.sandbox.impl.kubernetes;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Registers Jackson {@link com.fasterxml.jackson.databind.jsontype.NamedType} entries for the
 * Kubernetes sandbox backend. Combine with {@link
 * io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule} on the same {@link
 * com.fasterxml.jackson.databind.ObjectMapper} when mixing Docker and Kubernetes sandboxes.
 */
public final class KubernetesHarnessSandboxJacksonModule extends SimpleModule {

    public KubernetesHarnessSandboxJacksonModule() {
        super("harness-sandbox-kubernetes");
        registerSubtypes(new NamedType(KubernetesSandboxState.class, "kubernetes"));
    }
}
