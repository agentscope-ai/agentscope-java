---
kind: dependency_management
name: Maven 多模块依赖与 BOM 统一治理
category: dependency_management
scope:
    - '**'
source_files:
    - pom.xml
    - agentscope-dependencies-bom/pom.xml
    - agentscope-distribution/agentscope-bom/pom.xml
    - .github/workflows/maven-ci.yml
---

## 体系概览

AgentScope Java 仓库采用 **Maven 聚合工程 + 双 BOM（Bill of Materials）** 的依赖治理模式，通过父子 POM 继承、`dependencyManagement` 集中声明版本、以及 `flatten-maven-plugin` 生成扁平化 POM，实现跨 20+ 子模块的版本一致性与可发布性。

### 1. 使用的工具与框架

- **构建与依赖管理**：Maven（Java 17），使用 Maven Multi-module 聚合器。
- **版本分发**：两个 BOM 包——`agentscope-dependencies-bom`（第三方库版本）与 `agentscope-bom`（AgentScope 自身模块坐标）。
- **POM 扁平化**：`flatten-maven-plugin`，在 `process-resources` 阶段生成不含 `${revision}` 等属性的 `.flattened-pom.xml`，用于 Sonatype Central 发布。
- **CI 缓存**：GitHub Actions 中通过 `actions/cache@v4` 缓存 `~/.m2/repository` 与 `~/.mvnd`，加速构建。
- **许可证检查**：`apache/skywalking-eyes/dependency` 在 CI 中校验依赖许可证合规性。

### 2. 核心文件与位置

| 角色 | 路径 |
|---|---|
| 根聚合 POM（定义 modules、全局属性、插件、distributionManagement） | `pom.xml` |
| 第三方依赖版本 BOM（所有第三方库 version 属性 + dependencyManagement） | `agentscope-dependencies-bom/pom.xml` |
| AgentScope 内部模块坐标 BOM（聚合 core + extensions + starters 等） | `agentscope-distribution/agentscope-bom/pom.xml` |
| CI 流水线（Maven 构建、mvnd、覆盖率上传） | `.github/workflows/maven-ci.yml` |
| 许可证清单（供 skywalking-eyes 校验） | `.licenserc.yaml` |

### 3. 架构与约定

#### 3.1 双层 BOM 分层

```
根 POM (agentscope-parent)
├── import agentscope-dependencies-bom   ← 第三方库版本
└── import agentscope-bom                ← 本仓库模块版本
```

- **`agentscope-dependencies-bom`**：集中声明所有第三方依赖的 `<version>` 为 `<properties>`（如 `okhttp.version=5.3.2`、`spring-boot.version=4.0.4`），并在 `<dependencyManagement>` 中以 `<scope>import</scope>` 引入各上游 BOM（reactor-bom、jackson-bom、opentelemetry-bom、mockito-bom、junit-bom 等），再显式列出需要固定版本的单体依赖（OpenAI SDK、DashScope SDK、pgvector、redisson 等）。
- **`agentscope-bom`**：仅声明 `io.agentscope:*` 下所有子模块的坐标与版本，供外部使用者以单一 BOM 引入整个生态。

#### 3.2 版本策略

- 项目级版本号通过 `${revision}` 统一管理（默认 `2.0.1-SNAPSHOT`），由 `flatten-maven-plugin` 在打包时替换为真实值。
- 第三方库版本全部收敛到 `agentscope-dependencies-bom` 的 `<properties>`，子模块引用时只写 `<groupId>/<artifactId>`，不写 `<version>`。
- 对存在多个兼容版本的组件（如 Spring WebFlux vs Spring Boot Autoconfigure），分别用独立 property 区分，避免隐式冲突。

#### 3.3 依赖排除与冲突消解

BOM 中对已知冲突进行显式 `<exclusions>`，例如：
- `a2a-java-sdk-server-common` 排除 `microprofile-config-api`；
- `jsonschema-generator` 排除 `jackson-core`（由 jackson-bom 统一版本）。

#### 3.4 仓库与发布

- `distributionManagement` 指向 Sonatype OSS Nexus staging 仓库（release 与 snapshot 分开）。
- `profiles.release` 启用 GPG 签名与 `central-publishing-maven-plugin`，通过 GitHub Secrets 完成认证。
- 未在本仓库内配置自定义 `<repositories>`，默认拉取 Maven Central。

### 4. 开发者应遵循的规则

1. **新增第三方依赖**：先在 `agentscope-dependencies-bom/pom.xml` 的 `<properties>` 中声明 `<xxx.version>`，再在 `<dependencyManagement>` 中添加对应条目，子模块直接引用不写版本。
2. **新增 AgentScope 子模块**：在 `agentscope-distribution/agentscope-bom/pom.xml` 的 `<dependencyManagement>` 中注册其坐标，以便外部通过 `agentscope-bom` 一键引入。
3. **禁止在业务模块中硬编码版本**：任何 `<version>` 都应来自父 POM 或 BOM 的属性，否则会被 `spotless` 之外的流程视为违规。
4. **处理传递依赖冲突**：若新依赖带来版本冲突，优先在 BOM 层添加 `<exclusions>` 或覆盖上游 BOM 中的版本，而非在子模块中局部排除。
5. **发布前检查**：确保 `agentscope-dependencies-bom` 与 `agentscope-bom` 同步更新，CI 中有脚本 `.github/scripts/check-shade-and-bom-sync.sh` 校验二者一致性。

### 5. 补充说明

- 仓库未使用 `mvn versions:use-latest-releases` 或 Renovate 等自动升级工具，版本升级由人工维护 BOM。
- 无私有 Maven 仓库或镜像配置，依赖均从 Maven Central 下载。
- 前端示例模块（如 `agentscope-builder/frontend`）使用独立的 `package.json`，但不在本仓库的 Java 依赖治理范围内。