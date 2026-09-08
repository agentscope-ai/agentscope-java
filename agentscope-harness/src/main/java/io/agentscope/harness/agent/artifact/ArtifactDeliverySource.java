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
 * Metadata for an artifact delivered without downloading its bytes into the host JVM.
 *
 * @param filePath normalized path in the supplied agent filesystem, not a host path
 * @param fileName validated plain destination file name
 * @param description optional artifact description
 * @param force whether an existing artifact may be overwritten
 */
public record ArtifactDeliverySource(
        String filePath, String fileName, String description, boolean force) {}
