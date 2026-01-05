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
package io.agentscope.core;

/**
 * AgentScope version and User-Agent information.
 *
 * <p>Provides a unified User-Agent string for all model requests to identify AgentScope Java
 * clients and collect usage statistics.
 */
public final class Version {

    /** AgentScope Java version */
    public static final String VERSION = "1.0.7-SNAPSHOT";

    private Version() {
        // Utility class - prevent instantiation
    }

    /**
     * Generate standard User-Agent string for all models.
     *
     * <p>Format: {@code agentscope-java/{version}; java/{java_version}; platform/{os}}
     *
     * <p>Example: {@code agentscope-java/1.0.7-SNAPSHOT; java/17.0.1; platform/Mac OS X}
     *
     * @return unified User-Agent string
     */
    public static String getUserAgent() {
        return String.format(
                "agentscope-java/%s; java/%s; platform/%s",
                VERSION, System.getProperty("java.version"), System.getProperty("os.name"));
    }
}
