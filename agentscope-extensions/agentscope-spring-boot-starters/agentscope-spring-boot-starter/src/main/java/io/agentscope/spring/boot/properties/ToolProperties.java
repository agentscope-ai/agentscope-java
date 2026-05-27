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
package io.agentscope.spring.boot.properties;

/**
 * Tool specific settings.
 *
 * <p>Automatic scanning and registration of Spring Beans annotated with {@code @Tool}
 * is enabled by default.
 *
 * <p>Example configuration to disable auto-scanning:
 *
 * <pre>{@code
 * agentscope:
 *   tool:
 *     auto-scan:
 *       enabled: false
 * }</pre>
 */
public class ToolProperties {

    /**
     * Auto-scan specific settings.
     */
    private final AutoScanProperties autoScan = new AutoScanProperties();

    public AutoScanProperties getAutoScan() {
        return autoScan;
    }

    /**
     * Configuration for automatic tool scanning.
     */
    public static class AutoScanProperties {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
