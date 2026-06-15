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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specification for the local filesystem mode (with shell execution).
 *
 * <p>This spec produces a {@link LocalFilesystemWithShell} whose root is the agent workspace and
 * whose shell runs directly on the host as {@code sh -c <command>}. Long-term memory
 * ({@code MEMORY.md}, {@code memory/}) and session logs live on the same local disk.
 *
 * <p>Suitable for single-process deployments (personal assistants, CLI tools, local dev loops)
 * where distributed sharing is not required and the agent is trusted to run host shell commands.
 *
 * <p>For distributed deployments where long-term memory must be shared across replicas, prefer
 * {@link RemoteFilesystemSpec} (no shell) or a sandbox filesystem spec (shell via sandbox).
 */
public class LocalFilesystemSpec {

    // shell 命令超时（秒），超过后强杀进程
    // 示例: .executeTimeoutSeconds(300)  // 长命令给 5 分钟
    private int executeTimeoutSeconds = LocalFilesystemWithShell.DEFAULT_EXECUTE_TIMEOUT;

    // stdout+stderr 截断上限（字节），防止大输出撑爆模型上下文
    // 示例: .maxOutputBytes(50_000)  // 限制 50KB
    private int maxOutputBytes = 100_000;

    // shell 环境变量注入，每次命令执行时注入到 ProcessBuilder
    // 示例: .env("GITHUB_TOKEN", "ghp_xxx")
    //       .env("PYTHONPATH", "/app/libs")
    private final Map<String, String> env = new LinkedHashMap<>();

    // 是否继承 JVM 父进程的所有环境变量
    //   false（默认）: 从零开始，只有 env() 注入的变量可见 — 安全
    //   true:         继承 System.getenv()，env() 同名项覆盖
    // 示例: .inheritEnv(true)   // 让 shell 能访问 PATH、HOME 等系统变量
    private boolean inheritEnv = false;

    // 路径虚拟化：锚定在 workspace 根目录，禁止 .. 逃逸
    //   agent 看到的是 /file.txt 而非 /data/workspace/file.txt
    // 示例: .virtualMode(true)  // agent 无法读到 workspace 外的文件
    private boolean virtualMode = false;

    /**
     * shell 命令执行超时（秒）。
     * <p>每次 agent 调用 {@code shell_execute} 工具时，
     * shell 进程最多存活这个时间，超时后 Process.destroyForcibly() 强杀。
     *
     * <pre>{@code
     * // 批量数据处理需要更长时间
     * .filesystem(new LocalFilesystemSpec()
     *     .executeTimeoutSeconds(600))
     * }</pre>
     */
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + seconds);
        }
        this.executeTimeoutSeconds = seconds;
        return this;
    }

    /**
     * shell 命令输出截断上限（字节）。
     * <p>stdout + stderr 的总长度超过此值时截断，
     * 末尾追加 "... Output truncated at xxx bytes." 标记。
     * 防止大文件 cat 或 verbose 命令把模型上下文撑爆。
     *
     * <pre>{@code
     * // 只取前 200KB 输出
     * .filesystem(new LocalFilesystemSpec()
     *     .maxOutputBytes(200_000))
     * }</pre>
     */
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive, got " + bytes);
        }
        this.maxOutputBytes = bytes;
        return this;
    }

    /**
     * 注入 shell 环境变量。
     * <p>每次命令执行时写入 ProcessBuilder.environment()。
     * 可多次调用逐条添加。注意 token 类变量安全，不要暴露给调试日志。
     *
     * <pre>{@code
     * // 给 shell 注入 API token 和自定义路径
     * .filesystem(new LocalFilesystemSpec()
     *     .env("GITHUB_TOKEN", System.getenv("GITHUB_TOKEN"))
     *     .env("PYTHONPATH", "/opt/tools"))
     * }</pre>
     */
    public LocalFilesystemSpec env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        this.env.put(name, value);
        return this;
    }

    /**
     * 是否继承 JVM 父进程的环境变量。
     *
     * <p>{@code false}（默认）：shell 环境从零开始，只有 {@link #env(String, String)}
     * 注入的变量可见 — 不会把 JAVA_HOME、AWS_SECRET 等主机变量暴露给 agent。
     * <p>{@code true}：先拷贝 System.getenv()，再用 env() 覆盖同名项，
     * 适合 agent 需要用 PATH、HOME 等系统变量的场景。
     *
     * <pre>{@code
     * // 继承父进程环境，让 shell 能访问系统工具链
     * .filesystem(new LocalFilesystemSpec()
     *     .inheritEnv(true))
     * }</pre>
     */
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.inheritEnv = inherit;
        return this;
    }

    /**
     * 路径虚拟化模式。
     *
     * <p>启用后：
     * <ul>
     *   <li>所有路径锚定在 workspace 根目录，agent 看到的是 {@code /file.txt}
     *       而非 {@code /data/workspace/file.txt}
     *   <li>禁止 {@code ..} 目录穿越和 {@code ~} 引用，违者抛 SecurityException
     * </ul>
     *
     * <pre>{@code
     * // agent 无法访问 workspace 外文件
     * .filesystem(new LocalFilesystemSpec()
     *     .virtualMode(true))
     * }</pre>
     */
    public LocalFilesystemSpec virtualMode(boolean virtual) {
        this.virtualMode = virtual;
        return this;
    }

    /**
     * Builds the effective filesystem rooted at {@code workspace}.
     *
     * @param workspace agent workspace root
     * @param localNamespaceFactory optional namespace factory for per-user/session folder scoping
     * @return a {@link LocalFilesystemWithShell} wired with the options in this spec
     */
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
        return new LocalFilesystemWithShell(
                workspace,
                virtualMode,
                executeTimeoutSeconds,
                maxOutputBytes,
                env.isEmpty() ? null : Map.copyOf(env),
                inheritEnv,
                localNamespaceFactory);
    }
}
