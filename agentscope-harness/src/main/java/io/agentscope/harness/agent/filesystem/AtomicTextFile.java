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
package io.agentscope.harness.agent.filesystem;

/**
 * An authoritative UTF-8 file with conditional whole-file replacement. Missing is {@code null};
 * read/write failures throw. Conditional replacement is atomic across cooperating writers;
 * implementations may use locks or native CAS, without a local fallback on failure.
 */
public interface AtomicTextFile {
    String read();

    /** {@code expected == null} means create only if absent. {@code updated} cannot be null. */
    boolean compareAndSet(String expected, String updated);
}
