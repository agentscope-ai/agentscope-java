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
/**
 * Reliable tool execution and delivery components, packaged as an embeddable library.
 *
 * <p>{@link io.agentscope.durable.worker.QueuedExecutor} turns on the caller side (Brain or backend
 * service) and persists command/result delivery. {@link io.agentscope.durable.worker.WorkerRuntime}
 * turns on the Hands side and manages tool execution through an
 * {@link io.agentscope.durable.worker.ExecutionStore}. A
 * {@link io.agentscope.durable.worker.LocalWorkerExecutor} exposes a colocated WorkerRuntime to
 * Brain without making the runtime itself an Executor. They may share a JVM or turn in separate
 * hosts; depending on this library does not start either component. Transport extensions wire the
 * two sides. {@link io.agentscope.durable.worker.BatchExecutor} composes child tool executions.
 */
package io.agentscope.durable.worker;
