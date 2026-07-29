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
package io.agentscope.core.a2a.server.auth;

/** Stable error codes for HTTP admission failures. */
public final class A2aAuthErrorCodes {

    public static final String AUTH_REQUIRED = "A2A_AUTH_REQUIRED";
    public static final String TOKEN_INVALID_OR_EXPIRED = "A2A_TOKEN_INVALID_OR_EXPIRED";
    public static final String CALLER_FORBIDDEN = "A2A_CALLER_FORBIDDEN";
    public static final String USER_ID_MISMATCH = "A2A_USER_ID_MISMATCH";
    public static final String DELEGATED_USER_REQUIRED = "A2A_DELEGATED_USER_REQUIRED";
    public static final String AUTH_UNAVAILABLE = "A2A_AUTH_UNAVAILABLE";

    private A2aAuthErrorCodes() {}
}
