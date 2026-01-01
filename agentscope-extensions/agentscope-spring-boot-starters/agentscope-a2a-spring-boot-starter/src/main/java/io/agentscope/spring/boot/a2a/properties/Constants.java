/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.spring.boot.a2a.properties;

/**
 * AgentScope constants for spring boot autoconfiguration.
 */
public class Constants {

    public static final String AGENTSCOPE_PREFIX = "agentscope";

    public static final String A2A_PREFIX = AGENTSCOPE_PREFIX + ".a2a";

    public static final String A2A_SERVER_PREFIX = A2A_PREFIX + ".server";

    public static final String A2A_SERVER_CARD_PREFIX = A2A_SERVER_PREFIX + ".card";

    public static final String DEFAULT_SERVER_EXPORT_PORT = "server.port";

    public static final String DEFAULT_SERVER_EXPORT_ADDRESS = "server.address";
}
