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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.extensions.judge.jev.JevClient;
import org.junit.jupiter.api.Test;

class JevServiceSupportTest {
    @Test
    void disabledDoesNotResolveCredentials() {
        var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError();
                        });
        try {
            assertThat(support.middlewares(null)).isEmpty();
            assertThat(support.middlewares("{\"jev\":{\"tools\":{\"mode\":\"OFF\"}}}")).isEmpty();
        } finally {
            support.close();
        }
    }

    @Test
    void enabledPurposesBuildAndRejectUntrustedOptions() {
        var support =
                new JevServiceSupport(
                        "", () -> JevClient.builder().apiKey("test-not-real").build());
        try {
            assertThat(
                            support.middlewares(
                                    "{\"jev\":{\"tools\":{\"mode\":\"SHADOW\"},\"guard\":{\"mode\":\"ENFORCE\",\"guardedTools\":[\"refund\"]}}}"))
                    .hasSize(2);
            assertThatThrownBy(
                            () -> support.middlewares("{\"jev\":{\"tools\":{\"apiKey\":\"bad\"}}}"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    support.middlewares(
                                            "{\"jev\":{\"routing\":{\"mode\":\"SHADOW\",\"models\":[\"not-allowed\"]}}}"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            support.close();
        }
    }

    @Test
    void configChangesCacheIdentity() {
        var off =
                new SessionAgentBuildSpec(
                        1, "env", null, "{\"jev\":{\"tools\":{\"mode\":\"OFF\"}}}", null, null);
        var shadow =
                new SessionAgentBuildSpec(
                        1, "env", null, "{\"jev\":{\"tools\":{\"mode\":\"SHADOW\"}}}", null, null);
        assertThat(off.cacheSuffix()).isNotEqualTo(shadow.cacheSuffix());
    }

    @Test
    void contentAndQualityShareOneResponseMiddleware() {
        var support =
                new JevServiceSupport(
                        "", () -> JevClient.builder().apiKey("test-not-real").build());
        try {
            assertThat(
                            support.middlewares(
                                    "{\"jev\":{\"content\":{\"mode\":\"OFF\"},\"quality\":{\"mode\":\"OFF\"}}}"))
                    .isEmpty();
            var config =
                    """
                    {"jev":{"content":{"mode":"SHADOW","blockOnReview":true},
                     "quality":{"mode":"ENFORCE","maxRevisions":1,"criteria":[
                        {"id":"grounded","instructions":"Is answer supported by prompt?"}]}}}
                    """;
            assertThat(support.middlewares(config)).hasSize(1);
            assertThat(support.middlewares(config).get(0))
                    .isInstanceOf(
                            io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware
                                    .class);
            assertThatThrownBy(
                            () ->
                                    support.middlewares(
                                            "{\"jev\":{\"quality\":{\"mode\":\"ENFORCE\"}}}"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            support.close();
        }
    }
}
