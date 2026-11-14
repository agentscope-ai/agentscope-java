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
package io.agentscope.durable.examples.httpserver;

import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.SessionBackend;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable resources owned by the Spring application context. */
@Configuration(proxyBeanMethods = false)
class HttpServerConfiguration {
    @Bean
    SessionBackend durableBackend(HttpServerProperties properties) throws IOException {
        Files.createDirectories(properties.getDataDirectory());
        return LocalBackend.open(properties.getDataDirectory());
    }

    @Bean
    DurableRuntime durableRuntime(SessionBackend backend) {
        return DurableRuntime.builder()
                .backend(backend)
                .definition("http-server-demo", "v1", "1")
                .build();
    }
}
