# 贡献指南

English version: [`CONTRIBUTING.md`](CONTRIBUTING.md).

感谢你参与 Aistio。本指南说明如何准备本地环境、提交聚焦的变更，并让 Review PR 更容易。

## 开始之前

- 开始较大改动前，先查看已有 issue 和 pull request。
- 每个 PR 尽量只解决一个行为、一个包或一个文档主题。
- 不要把尚未实现的 API 写成已可用功能。
- 除非维护者明确要求，不要新增 release 自动化或版本号发布流程。
- 如果使用 AI 辅助开发，贡献者仍需自行审阅、测试并解释最终变更。

## 开发环境

推荐工具版本：

| 工具 | 版本 |
| --- | --- |
| Go | `1.26+` |
| GNU Make | 近期版本即可 |
| controller-gen | 最新版本 |
| Helm | `3.x` |

初始化仓库：

```bash
make install-tools
```

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `make build` | 构建 `aistiod` 二进制。 |
| `make test` | 执行 `go test ./...` 并生成覆盖率。 |
| `make test-integration` | 执行 envtest 集成测试。 |
| `make fmt` | 格式化 Go 代码。 |
| `make vet` | 执行 `go vet ./...`。 |
| `make generate` | 重新生成 deepcopy 方法。 |
| `make manifests` | 重新生成 CRD、RBAC 和 webhook manifests。 |
| `make sync-helm` | 将生成的 CRD 和 RBAC 同步到 Helm chart。 |
| `make verify` | 验证生成的代码和 manifests 是最新的。 |
| `make helm-lint` | Lint Helm chart。 |
| `make docker-build` | 构建多架构 Docker 镜像。 |

## 仓库约定

- 公共 API 保持小而明确，符合 Go 习惯。
- 新增包前先匹配现有包边界，避免把无关能力堆到根包。
- Go 注释和 public API 文档统一使用英文。
- 面向中文用户的文档写入 `*_zh.md` 文件。
- 新增 Go 文件使用项目统一 license header：

```go
// Copyright The Aistio Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
```

- 能使用标准库或成熟生态库时，不自研替代实现。
- 错误需要带足上下文，便于调用方判断失败的包、操作和外部依赖。
- 不要提交 API Key、模型凭据、生成密钥或私有 MCP Server 配置。

## 测试要求

代码改动建议至少运行：

```bash
make fmt
make vet
make test
```

CRD 或 webhook 改动还需运行：

```bash
make generate
make manifests
make verify
```

## PR 检查清单

提交 PR 前请确认：

- 变更动机和范围清楚。
- 需要时已同步更新代码、测试和文档。
- 相关本地检查已通过。
- 跳过的检查已在 PR 中说明原因。
- 涉及 Agent 执行、凭据处理等安全敏感行为时，已明确写出影响。

请使用仓库 PR 模板，并保持 PR 标题简洁。

## 安全问题

请不要通过公开 issue 报告漏洞。私密报告方式见 [`SECURITY_zh.md`](SECURITY_zh.md)。

## 行为准则

项目内所有协作空间均遵守 [`CODE_OF_CONDUCT_zh.md`](CODE_OF_CONDUCT_zh.md)。
