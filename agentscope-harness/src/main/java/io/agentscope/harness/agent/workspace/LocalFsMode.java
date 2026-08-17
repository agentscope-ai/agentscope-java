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
package io.agentscope.harness.agent.workspace;

/**
 * Path-resolution policy mode for host-rooted filesystems
 * ({@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} and friends).
 *
 * <p>Controls what happens when the agent supplies an absolute path:
 *
 * <ul>
 *   <li>{@link #SANDBOXED} — anchor every path to the filesystem root, reject {@code ..} and
 *       absolute paths leaving the root. Equivalent to the legacy {@code virtualMode=true}.
 *   <li>{@link #ROOTED} — absolute paths are accepted only when they fall under one of the
 *       roots in the configured {@link PathPolicy} (project + workspace + additional roots).
 *       Relative paths still resolve against the filesystem root. This is the default for
 *       Local-mode agents and matches the Claude-Code-style "project + additional dirs"
 *       allow-list.
 *   <li>{@link #UNRESTRICTED} — absolute paths pass through unchanged. Equivalent to the legacy
 *       {@code virtualMode=false}. Escape hatch for tools or tests that need to read arbitrary
 *       host paths.
 * </ul>
 */
public enum LocalFsMode {
    SANDBOXED,
    ROOTED,
    UNRESTRICTED
}
