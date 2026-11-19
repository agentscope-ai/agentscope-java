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
 * Result of an {@link ArtifactDeliveryTarget#deliver} attempt.
 *
 * @param successful whether the artifact was delivered
 * @param conflict whether the delivery was rejected because an artifact with the same name already
 *     exists and {@code force} was {@code false}
 * @param error error description on failure, {@code null} on success
 * @param message human-readable confirmation detail on success (may be {@code null})
 */
public record ArtifactDeliveryResult(
        boolean successful, boolean conflict, String error, String message) {

    public static ArtifactDeliveryResult success() {
        return new ArtifactDeliveryResult(true, false, null, null);
    }

    public static ArtifactDeliveryResult success(String message) {
        return new ArtifactDeliveryResult(true, false, null, message);
    }

    public static ArtifactDeliveryResult fail(String error) {
        return new ArtifactDeliveryResult(false, false, error, null);
    }

    public static ArtifactDeliveryResult conflict(String error) {
        return new ArtifactDeliveryResult(false, true, error, null);
    }
}
