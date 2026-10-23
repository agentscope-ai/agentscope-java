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
package io.agentscope.builder.web.managed;

import java.util.Set;

/**
 * Environment template type constants, shared across planes. The control plane owns environment
 * lifecycle; the data plane reads these types when building agents and acquiring hands leases.
 */
public final class EnvironmentTypes {

    /** Local host filesystem hands. */
    public static final String TYPE_LOCAL = "local";

    /** Cloud-equivalent Docker hands (E2B sandbox). */
    public static final String TYPE_SANDBOX = "sandbox";

    /** Distributed KV filesystem, no shell. */
    public static final String TYPE_REMOTE = "remote";

    /** Worker-owned hands via {@code externalSandbox} (self-hosted environment workers). */
    public static final String TYPE_SELF_HOSTED = "self_hosted";

    /** ACL resource type used for environment share checks. */
    public static final String RESOURCE_TYPE = "environment";

    public static final Set<String> VALID_TYPES =
            Set.of(TYPE_LOCAL, TYPE_SANDBOX, TYPE_REMOTE, TYPE_SELF_HOSTED);

    private EnvironmentTypes() {}
}
