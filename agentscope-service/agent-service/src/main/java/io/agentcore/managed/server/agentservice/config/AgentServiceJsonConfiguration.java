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
package io.agentcore.managed.server.agentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/** Keep HTTP JSON on the same mapper as the persisted protocol and its JsonNode DTOs. */
@Configuration(proxyBeanMethods = false)
public class AgentServiceJsonConfiguration implements WebFluxConfigurer {
    private final ObjectMapper mapper;

    public AgentServiceJsonConfiguration(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer codecs) {
        codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
        codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
    }
}
