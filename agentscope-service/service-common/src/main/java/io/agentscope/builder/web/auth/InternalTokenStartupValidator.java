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
package io.agentscope.builder.web.auth;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails fast when the shared cross-plane internal token is missing or uses a known development
 * default outside the {@code dev} profile.
 */
@Component
public class InternalTokenStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenStartupValidator.class);

    private static final Set<String> KNOWN_DEV_DEFAULTS =
            new HashSet<>(
                    Arrays.asList(
                            "dev-internal-token",
                            "compose-dev-internal-token",
                            "changeme",
                            "internal"));

    private final String internalToken;
    private final Environment environment;

    public InternalTokenStartupValidator(
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            Environment environment) {
        this.internalToken = internalToken;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        boolean lenient = profiles.contains("dev") || profiles.contains("test");
        if (internalToken == null || internalToken.isBlank()) {
            if (lenient) {
                log.warn(
                        "builder.internal-token is blank; cross-plane internal APIs stay closed"
                                + " until a token is configured");
                return;
            }
            throw new IllegalStateException(
                    "builder.internal-token (or BUILDER_INTERNAL_TOKEN) must be set outside the"
                            + " 'dev'/'test' profiles");
        }
        if (lenient) {
            return;
        }
        if (internalToken.length() < 32) {
            throw new IllegalStateException(
                    "builder.internal-token must be at least 32 characters outside the"
                            + " 'dev'/'test' profiles");
        }
        if (KNOWN_DEV_DEFAULTS.contains(internalToken)) {
            throw new IllegalStateException(
                    "builder.internal-token must not use a known development default outside the"
                            + " 'dev'/'test' profiles");
        }
    }
}
