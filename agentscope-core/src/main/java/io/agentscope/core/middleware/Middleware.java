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
package io.agentscope.core.middleware;

/**
 * Type alias retained for source-compatibility with code written against the original
 * {@code Middleware} interface name. New code should target {@link MiddlewareBase} directly; this
 * interface adds no extra contract and inherits all behaviour (including default methods) from
 * its supertype.
 */
public interface Middleware extends MiddlewareBase {}
