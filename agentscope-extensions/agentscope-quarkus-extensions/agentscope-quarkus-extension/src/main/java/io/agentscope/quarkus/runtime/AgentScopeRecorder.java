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
package io.agentscope.quarkus.runtime;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Recorder for build-time initialization of AgentScope.
 * This class is used during the build phase to record configuration
 * that will be replayed at runtime.
 */
@Recorder
public class AgentScopeRecorder {

    /**
     * Initialize AgentScope with the given configuration.
     * This method is called during build time and the configuration
     * is recorded for runtime use.
     *
     * @param config the AgentScope configuration
     */
    public void initialize(AgentScopeConfig config) {
        // Configuration is recorded at build time
        // and will be available at runtime
    }
}
