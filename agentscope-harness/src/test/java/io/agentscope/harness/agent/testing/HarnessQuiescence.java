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
package io.agentscope.harness.agent.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Composed annotation that registers {@link HarnessBackgroundTaskQuiescenceExtension} so
 * fire-and-forget harness background tasks (memory flush/maintenance, session/transcript
 * mirrors) are drained after each test, before JUnit deletes its {@code @TempDir}.
 *
 * <p>Apply to any test that builds a {@code HarnessAgent}, drives it to completion
 * ({@code .block()} / {@code .stream()...block()}), and uses {@code @TempDir} for the
 * workspace or state home. Without this, the async memory flush dispatched in
 * {@code MemoryFlushMiddleware#onAgent}'s {@code doOnComplete} races with
 * {@code @TempDir} teardown and surfaces as the flaky
 * {@code "Failed to delete temp directory"} / {@code "Failed to close extension context"}
 * error. Calling {@code HarnessAgent.close()} has the same effect; this annotation covers
 * tests that build a transient agent and never close it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(HarnessBackgroundTaskQuiescenceExtension.class)
public @interface HarnessQuiescence {}
