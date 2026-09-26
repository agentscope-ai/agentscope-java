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
package io.agentscope.extensions.sandbox.kubernetes.client;

/** Constants for agent-sandbox CRDs and labels, mirroring the Python SDK. */
public final class Constants {

    public static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    public static final String GATEWAY_API_VERSION = "v1";
    public static final String GATEWAY_PLURAL = "gateways";

    public static final String CLAIM_API_GROUP = "extensions.agents.x-k8s.io";
    public static final String CLAIM_API_VERSION = "v1beta1";
    public static final String CLAIM_PLURAL_NAME = "sandboxclaims";

    public static final String SANDBOX_API_GROUP = "agents.x-k8s.io";
    public static final String SANDBOX_API_VERSION = "v1beta1";
    public static final String SANDBOX_PLURAL_NAME = "sandboxes";

    public static final String POD_NAME_ANNOTATION = "agents.x-k8s.io/pod-name";
    public static final String CREATED_BY_LABEL = "agents.x-k8s.io/created-by";
    public static final String CREATED_BY_VALUE = "agentscope-java-client";
    public static final String SANDBOX_NAME_HASH_LABEL = "agents.x-k8s.io/sandbox-name-hash";

    public static final String ROUTER_SERVICE_NAME = "sandbox-router-svc";
    public static final int ROUTER_PORT = 8080;
    public static final int DEFAULT_SERVER_PORT = 8888;

    private Constants() {}
}
