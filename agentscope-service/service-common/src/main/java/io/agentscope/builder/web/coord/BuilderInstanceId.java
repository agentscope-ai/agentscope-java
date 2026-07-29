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
package io.agentscope.builder.web.coord;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stable-enough identity for this Brain / worker JVM, used as lease owner ids. */
@Component
public class BuilderInstanceId {

    private final String id;

    public BuilderInstanceId(
            @Value("${builder.instance-id:}") String configured,
            @Value("${builder.instance-id-prefix:brain}") String prefix) {
        if (configured != null && !configured.isBlank()) {
            this.id = configured.trim();
        } else {
            String host = "unknown";
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                // keep fallback
            }
            String pid = ManagementFactory.getRuntimeMXBean().getName();
            this.id =
                    prefix
                            + "-"
                            + host
                            + "-"
                            + pid
                            + "-"
                            + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public String get() {
        return id;
    }
}
