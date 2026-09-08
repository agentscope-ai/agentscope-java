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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;

/**
 * Opt-in delivery SPI that receives a source path instead of materialized file bytes.
 *
 * <p>Configure through the existing {@code artifactDeliveryTarget} builder method. The tool
 * invokes {@link #deliverFromFilesystem} without calling {@code downloadFiles}. Implementations
 * can upload within the sandbox using a backend SDK or shell, or stream from their backing store.
 * Existing byte-based targets continue to use {@link ArtifactDeliveryTarget} unchanged.
 *
 * <p>The target must resolve the source in the supplied filesystem and runtime context, respecting
 * its routing and access policy; a normalized path is not necessarily a native sandbox path.
 * It must check source existence and report upload failures and destination conflicts. Do not
 * interpolate untrusted paths into shell commands or expose upload credentials in tool results.
 * A failure never triggers an automatic byte-download fallback.
 */
@FunctionalInterface
public interface DirectArtifactDeliveryTarget extends ArtifactDeliveryTarget {

    /**
     * Delivers a file directly from its backing environment.
     *
     * @param runtimeContext per-call runtime, possibly {@code null}; use it for sandbox resolution
     * @param filesystem the active agent filesystem, potentially an overlay or routed filesystem
     * @param source validated destination metadata and normalized source path; contains no bytes
     * @return delivery result, never {@code null}
     */
    ArtifactDeliveryResult deliverFromFilesystem(
            RuntimeContext runtimeContext,
            AbstractFilesystem filesystem,
            ArtifactDeliverySource source);

    /** Direct targets require the source filesystem rather than a byte-based request. */
    @Override
    default ArtifactDeliveryResult deliver(
            RuntimeContext runtimeContext, ArtifactDeliveryRequest request) {
        return ArtifactDeliveryResult.fail("Direct artifact delivery requires a source filesystem");
    }
}
