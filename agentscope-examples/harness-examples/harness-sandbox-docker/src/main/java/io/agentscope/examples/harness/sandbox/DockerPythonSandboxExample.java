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
package io.agentscope.examples.harness.sandbox;

import static io.agentscope.examples.harness.common.util.ExampleUtils.ctx;
import static io.agentscope.examples.harness.common.util.ExampleUtils.getDashScopeApiKey;
import static io.agentscope.examples.harness.common.util.ExampleUtils.printWelcome;
import static io.agentscope.examples.harness.common.util.ExampleUtils.startChat;
import static io.agentscope.examples.harness.sandbox.DockerPythonSandboxImage.ensureImage;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class DockerPythonSandboxExample {

    public static void main(String[] args) throws Exception {
        printWelcome(
                "AgentScope Harness Docker Python Sandbox",
                "Interactive assistant that can execute Python scripts inside a Docker sandbox.");

        ensureImage();

        Path workspace = Paths.get(".agentscope/python-sandbox-workspace");
        initWorkspaceIfAbsent(workspace);

        String apiKey = getDashScopeApiKey();
        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                        .build();

        SandboxFilesystemSpec sandboxSpec =
                new DockerFilesystemSpec()
                        .image(DockerPythonSandboxImage.IMAGE)
                        .network("none")
                        .workspaceRoot("/workspace")
                        .workspaceProjectionRoots(List.of("AGENTS.md", "examples"));
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("docker-python-sandbox-agent")
                        .sysPrompt("你是一个 Python 数据分析助手。需要运行代码时，使用 shell 执行工具在 sandbox 中运行命令。")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(sandboxSpec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .maxIters(20)
                        .build();

        RuntimeContext ctx = ctx("docker-python-demo-session", "alice");
        System.out.println("Workspace: " + workspace.toAbsolutePath());
        System.out.println("Try: 请运行 examples/check_libs.py 检查 sandbox 里的 Python 环境。\n");
        startChat(agent, ctx);
    }

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("examples"));
        writeIfAbsent(
                workspace.resolve("AGENTS.md"),
                """
                # Docker Python Sandbox Assistant

                你运行在一个 Docker sandbox 工作区中。

                ## 可用能力
                - 使用 shell 执行工具运行 Python 代码和命令
                - 优先在 `/workspace` 下读写文件
                - 可以执行 `python examples/check_libs.py` 检查预装库
                - 可以执行 `python examples/analyze_sales.py` 运行示例数据分析脚本

                ## 行为约定
                - 执行代码前先简要说明计划
                - 执行失败时读取错误输出并修复脚本
                - 不要声称已运行代码，除非确实调用了 shell 执行工具
                """);
        writeIfAbsent(
                workspace.resolve("examples/check_libs.py"),
                """
                import importlib
                import sqlite3
                import subprocess
                import sys

                modules = [
                    "pandas",
                    "numpy",
                    "openpyxl",
                    "xlsxwriter",
                    "xlrd",
                    "PIL",
                    "pptx",
                    "docx",
                    "pypdf",
                    "pdfplumber",
                    "pypdfium2",
                    "pdf2image",
                    "img2pdf",
                    "sympy",
                    "mpmath",
                    "tqdm",
                    "dateutil",
                    "pytz",
                    "joblib",
                ]

                print(sys.version)
                print("sqlite", sqlite3.sqlite_version)
                missing = []
                for module in modules:
                    try:
                        importlib.import_module(module)
                    except Exception as exc:
                        missing.append((module, str(exc)))
                for command in ["rg", "fd", "unzip", "unrar", "7z", "bc"]:
                    completed = subprocess.run(["sh", "-lc", f"command -v {command}"], capture_output=True, text=True)
                    if completed.returncode != 0:
                        missing.append((command, "command not found"))
                if missing:
                    print("Missing dependencies:")
                    for name, reason in missing:
                        print(f"- {name}: {reason}")
                    raise SystemExit(1)
                print("All expected Python modules and utility commands are available.")
                """);
        writeIfAbsent(
                workspace.resolve("examples/analyze_sales.py"),
                """
                import pandas as pd

                data = pd.DataFrame(
                    [
                        {"region": "north", "product": "notebook", "revenue": 1200},
                        {"region": "north", "product": "pen", "revenue": 300},
                        {"region": "south", "product": "notebook", "revenue": 900},
                        {"region": "south", "product": "pen", "revenue": 450},
                    ]
                )

                summary = data.groupby("region", as_index=False)["revenue"].sum()
                print(summary.to_string(index=False))
                """);
    }

    private static void writeIfAbsent(Path path, String content) throws Exception {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
