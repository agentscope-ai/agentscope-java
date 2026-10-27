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
package io.agentscope.extensions.channel.common;

import java.util.Objects;

/**
 * A platform access token together with the refresh deadline chosen by the provider that fetched
 * it.
 *
 * @param value the token string accepted by the platform API; never {@code null} — absence of a
 *     token is represented by {@link AccessTokenStore#get()} returning {@code null}
 * @param refreshAtMs wall-clock epoch milliseconds after which the owning provider fetches a new
 *     token instead of returning this one
 */
public record CachedAccessToken(String value, long refreshAtMs) {

    public CachedAccessToken {
        Objects.requireNonNull(value, "value");
    }
}
