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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.control.ControlPlaneClient;
import org.springframework.stereotype.Service;

/**
 * Data-plane read access to environment templates. Environment rows live in the control-plane
 * schema; this service fetches them via {@link ControlPlaneClient}.
 */
@Service
public class DataEnvironmentService {

    private final ControlPlaneClient controlPlaneClient;

    public DataEnvironmentService(ControlPlaneClient controlPlaneClient) {
        this.controlPlaneClient = controlPlaneClient;
    }

    /** Returns the environment, or 404 when unknown. */
    public EnvironmentDto requireById(String environmentId) {
        return controlPlaneClient.getEnvironment(environmentId);
    }

    /**
     * Returns the environment for a turn build. Access was already validated by the control plane
     * when the session was created, so no per-call ACL check is repeated here.
     */
    public EnvironmentDto get(String ownerId, String environmentId) {
        return controlPlaneClient.getEnvironment(environmentId, ownerId);
    }
}
