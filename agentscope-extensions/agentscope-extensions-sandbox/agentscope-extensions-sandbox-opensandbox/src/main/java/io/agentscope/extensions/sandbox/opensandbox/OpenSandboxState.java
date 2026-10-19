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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.SandboxState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable state for an OpenSandbox-backed sandbox. */
public class OpenSandboxState extends SandboxState {

    public static final String DEFAULT_WORKSPACE_ROOT = "/workspace";

    private String sandboxId;
    private boolean sandboxOwned = true;
    private String image = "ubuntu:22.04";
    private List<String> entrypoint = List.of("tail", "-f", "/dev/null");
    private Map<String, String> resourceLimits = Map.of("cpu", "1", "memory", "2Gi");
    private int sandboxTimeoutSeconds = 600;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public boolean isSandboxOwned() {
        return sandboxOwned;
    }

    public void setSandboxOwned(boolean sandboxOwned) {
        this.sandboxOwned = sandboxOwned;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getEntrypoint() {
        return List.copyOf(entrypoint);
    }

    public void setEntrypoint(List<String> entrypoint) {
        this.entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
    }

    public Map<String, String> getResourceLimits() {
        return Map.copyOf(resourceLimits);
    }

    public void setResourceLimits(Map<String, String> resourceLimits) {
        this.resourceLimits =
                resourceLimits == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceLimits));
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }
}
