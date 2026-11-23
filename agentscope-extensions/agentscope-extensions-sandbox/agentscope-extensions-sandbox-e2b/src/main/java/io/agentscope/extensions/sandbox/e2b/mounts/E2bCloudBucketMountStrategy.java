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
package io.agentscope.extensions.sandbox.e2b.mounts;

/**
 * Placeholder for E2B cloud bucket mount configuration.
 *
 * @deprecated Superseded by {@link io.agentscope.extensions.sandbox.e2b.E2bVolumeMount}
 *     attach-at-create mounts. Kept for one transition release so downstream consumers
 *     referencing this type keep compiling; it carries no behavior and will be removed
 *     afterwards.
 */
@Deprecated
public interface E2bCloudBucketMountStrategy {
    // intentionally empty — extend when wiring E2B volume mounts from Harness workspace specs
}
