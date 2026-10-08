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
package io.agentscope.extensions.sandbox.dockerdaemon;

import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;

/**
 * Immutable description of one container mount, mirroring {@code docker run --mount}.
 *
 * <p>Create via {@link #of(String, String, String, boolean)} which validates and normalizes.
 * The canonical constructor is left unvalidated so Jackson can deserialize persisted sandbox
 * state; {@link DockerDaemonSandbox} re-validates defensively before applying.
 */
public record MountSpec(String type, String source, String target, boolean readOnly) {

    private static final String TYPE_BIND = "bind";
    private static final String TYPE_VOLUME = "volume";
    private static final String TYPE_TMPFS = "tmpfs";

    public static MountSpec of(String type, String source, String target, boolean readOnly) {
        String normalizedType = normalizeType(type);
        String normalizedTarget = requireNonBlank(target, "target");
        String normalizedSource = null;
        if (!TYPE_TMPFS.equals(normalizedType)) {
            String trimmedSource = requireNonBlank(source, "source");
            normalizedSource =
                    TYPE_BIND.equals(normalizedType)
                            ? WorkspaceMountSupport.normalizedHostPath(trimmedSource)
                            : trimmedSource;
        }
        return new MountSpec(normalizedType, normalizedSource, normalizedTarget, readOnly);
    }

    private static String normalizeType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("Mount type must not be null");
        }
        String normalized = type.trim().toLowerCase();
        if (!TYPE_BIND.equals(normalized)
                && !TYPE_VOLUME.equals(normalized)
                && !TYPE_TMPFS.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported mount type: " + type);
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mount " + field + " must not be blank");
        }
        return value.trim();
    }

    /**
     * Converts this (already validated) spec to a docker-java {@link Mount} for
     * {@code HostConfig.withMounts}. Callers must re-validate state-loaded specs first; an
     * unsupported {@code type} results in an {@link IllegalStateException}.
     */
    public Mount toDockerJavaMount() {
        MountType mountType =
                switch (type) {
                    case TYPE_BIND -> MountType.BIND;
                    case TYPE_VOLUME -> MountType.VOLUME;
                    case TYPE_TMPFS -> MountType.TMPFS;
                    default -> throw new IllegalStateException("Unsupported mount type: " + type);
                };
        return new Mount()
                .withType(mountType)
                .withSource(source)
                .withTarget(target)
                .withReadOnly(readOnly);
    }
}
