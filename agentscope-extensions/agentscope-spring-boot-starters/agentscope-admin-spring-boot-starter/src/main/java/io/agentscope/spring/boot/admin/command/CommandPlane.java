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
package io.agentscope.spring.boot.admin.command;

/**
 * Which physical surface a command lives on.
 *
 * <ul>
 *   <li>{@link #DATA}: per-session operations a session owner or business caller invokes
 *       (compact, abort, export, ...). Served via REST under the admin base path.
 *   <li>{@link #CONTROL}: process-wide operations an SRE/operator invokes (status, drain,
 *       shutdown, doctor, ...). Served via Spring Boot Actuator on
 *       {@code management.server.port}.
 * </ul>
 */
public enum CommandPlane {
    DATA,
    CONTROL
}
