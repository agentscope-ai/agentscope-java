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
package io.agentscope.examples.agui.config;

import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Overrides the default WebFlux codec buffer limit (256KB) so that AG-UI requests
 * carrying long conversation history or large message payloads are not rejected
 * with {@code DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144}.
 *
 * <p>In Spring Boot 4.x the {@code spring.codec.max-in-memory-size} YAML property is
 * not always propagated to the decoders used by functional {@code RouterFunction}
 * endpoints (which is what the AG-UI starter exposes). Registering a
 * {@link CodecCustomizer} bean is the officially supported way and is guaranteed
 * to apply to every {@code CodecConfigurer} built by the framework.
 */
@Configuration
public class WebFluxCodecConfiguration {

    /** 16 MB, large enough for AG-UI payloads with long conversation history. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    @Bean
    public CodecCustomizer aguiCodecCustomizer() {
        return configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE);
    }
}
