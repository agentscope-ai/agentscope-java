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

/** Resolves a caller identity before A2A business dispatch starts. */
@FunctionalInterface
public interface A2aAuthResolver {

    A2aAuthentication resolve(A2aAuthRequest request);

    static A2aAuthResolver anonymous() {
        return request -> A2aAuthentication.anonymous();
    }
}
