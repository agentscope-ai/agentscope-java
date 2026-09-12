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

/**
 * A single artifact delivery request issued by the {@code deliver_artifact} tool.
 *
 * <p>The tool downloads the file bytes from the agent filesystem and hands them to a configured
 * {@link ArtifactDeliveryTarget}, which is responsible for storing the artifact in its destination
 * (e.g. a project's artifacts directory / artifact store, WebDAV, an object store).
 *
 * @param filePath path of the generated file in the agent workspace, normalized the same way as
 *     other file tools (relative to the workspace, or absolute where permitted by the active
 *     filesystem)
 * @param content file bytes downloaded from the filesystem
 * @param fileName target file name after delivery; defaults to the basename of {@code filePath}.
 *     Always a plain file name — the tool rejects values containing path separators, {@code .} or
 *     {@code ..}, so targets can resolve it directly
 * @param description short description of the artifact, may be {@code null}
 * @param force whether an existing artifact with the same {@code fileName} should be overwritten
 *     ({@code false} by default — the target rejects the delivery and the agent should retry with a
 *     new {@code fileName})
 */
public record ArtifactDeliveryRequest(
        String filePath, byte[] content, String fileName, String description, boolean force) {}
