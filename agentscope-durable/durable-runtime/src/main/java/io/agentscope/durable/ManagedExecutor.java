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
package io.agentscope.durable;

/**
 * Executor whose transport lifecycle is owned by {@link DurableRuntime}.
 *
 * <p>This is an internal assembly contract for executor implementations. Applications start only
 * the containing Runtime.
 */
public interface ManagedExecutor extends Executor {
    /** Called exactly once while the Runtime is built, before execution can begin. */
    void bind(String route, SessionBackend backend);

    /** Opens transport delivery. Repeated calls must be harmless. */
    void open();
}
