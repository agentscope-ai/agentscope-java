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
package io.agentscope.harness.agent.artifact;

import io.agentscope.core.agent.RuntimeContext;

/**
 * SPI for delivering artifacts produced by an agent (typically inside a sandbox) to a destination
 * outside the agent filesystem — the host, a project artifact store, an object store, WebDAV, etc.
 *
 * <p>The framework stays business-agnostic: the generic {@code deliver_artifact} tool downloads the
 * file bytes from the filesystem and delegates the actual transport to the configured
 * {@code ArtifactDeliveryTarget}, which the application implements.
 *
 * <p>Configure on {@link
 * io.agentscope.harness.agent.HarnessAgent.Builder#artifactDeliveryTarget(ArtifactDeliveryTarget)}.
 * When set, the {@code deliver_artifact} tool is registered and the sandbox system prompt instructs
 * the model to use it; when unset, no delivery tool is exposed.
 */
@FunctionalInterface
public interface ArtifactDeliveryTarget {

    /**
     * Delivers the artifact carried by {@code request} to its destination.
     *
     * @param runtimeContext per-call agent runtime; may be {@code null} when no merged context is
     *     available
     * @param request the artifact to deliver (source file path, bytes, target file name,
     *     description, and the {@code force} flag). {@code request.fileName()} is already validated
     *     by the tool to be a plain file name (no path separators, {@code .} or {@code ..}); the
     *     target decides how to map it to its destination layout.
     * @return delivery result; implementations must not return {@code null}
     */
    ArtifactDeliveryResult deliver(RuntimeContext runtimeContext, ArtifactDeliveryRequest request);
}
