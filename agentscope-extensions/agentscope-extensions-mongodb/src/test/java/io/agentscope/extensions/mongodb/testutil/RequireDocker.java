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
package io.agentscope.extensions.mongodb.testutil;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Composed annotation that disables the annotated test class when Docker is not available in the
 * current environment.
 *
 * <p>Place alongside {@code @Testcontainers} to prevent container-start failures on CI runners
 * without a Docker daemon (e.g. GitHub-hosted Windows runners):
 *
 * <pre>{@literal @}Testcontainers
 * @RequireDocker
 * class MyContainerTest { ... }
 * </pre>
 *
 * <p>On environments without Docker, the test class is reported as <em>skipped</em> (not failed),
 * keeping the build green while still running the full test suite on Docker-capable runners.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(DockerAvailabilityCondition.class)
public @interface RequireDocker {}
