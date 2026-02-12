/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.spring.boot.nacos.constants;

import io.agentscope.spring.boot.a2a.properties.Constants;

/**
 * Constants for Nacos.
 *
 * <p>Includes prefixes for Nacos configuration properties. These prefixes follow the agentscope spring boot starter
 * configuration prefix.
 */
public class NacosConstants {

    public static final String NACOS_PREFIX = Constants.AGENTSCOPE_PREFIX + ".nacos";

    public static final String NACOS_PROMPT_PREFIX = NACOS_PREFIX + ".prompt";

    public static final String A2A_NACOS_PREFIX = Constants.A2A_PREFIX + ".nacos";

    public static final String A2A_NACOS_REGISTRY_PREFIX = A2A_NACOS_PREFIX + ".registry";

    public static final String A2A_NACOS_DISCOVERY_PREFIX = A2A_NACOS_PREFIX + ".discovery";
}
