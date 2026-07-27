---
kind: build_system
name: Maven 多模块构建与发布体系
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
    - agentscope-dependencies-bom/pom.xml
    - agentscope-distribution/pom.xml
    - agentscope-distribution/agentscope-all/pom.xml
    - .github/workflows/maven-ci.yml
    - .github/scripts/check-shade-and-bom-sync.sh
---

## 1. 系统概览
AgentScope Java 采用 Maven 聚合工程 + BOM 双中心架构，以 `agentscope-parent` 为根聚合器，通过 `agentscope-dependencies-bom` 统一第三方依赖版本、通过 `agentscope-distribution/agentscope-bom` 暴露对外可引入的 AgentScope 子模块 BOM，形成“内部依赖收敛 + 外部消费简化”的两层版本治理。构建产物除常规 jar 外，还提供 `agentscope-all` 全量 shaded jar 供快速体验。

## 2. 关键文件与职责
- `pom.xml`（根）：声明 `agentscope-core` / `harness` / `extensions` / `examples` / `dependencies-bom` / `distribution` 六大顶层模块；集中配置 Spotless、Javadoc、Surefire、Jacoco、Source、Deploy 等插件；定义 `release` profile 启用 GPG 签名与 Sonatype Central Publishing。
- `agentscope-dependencies-bom/pom.xml`：集中管理 OpenTelemetry、OkHttp、Reactor、Jackson、JUnit/Mockito、OpenAI/Gemini/Anthropic/DashScope SDK、Spring/Spring Boot、Nacos、Redisson、pgvector、Tree-sitter 等所有第三方依赖的版本号，并通过 `<dependencyManagement>` 提供 BOM。
- `agentscope-distribution/agentscope-all/pom.xml`：将所有 core 与 extensions 子模块声明为 `optional=true` 依赖，使用 `maven-shade-plugin` 打包成单一 fat jar，便于用户一键引入。
- `agentscope-distribution/agentscope-bom/pom.xml`：对外发布的 AgentScope 子模块 BOM，供下游项目直接 `<dependencyManagement><import/></dependencyManagement>` 使用。
- `.github/workflows/maven-ci.yml`：在 Ubuntu/Windows 双矩阵上执行 License 检查、扩展模块与 shade/BOM 同步校验、mvnd 加速构建与测试、Codecov 覆盖率上传。
- `.github/scripts/check-shade-and-bom-sync.sh`：CI 中用于确保新增 extension 模块同步写入 `agentscope-all` 与 `agentscope-bom` 的脚本。

## 3. 架构与约定
- **版本策略**：根 POM 使用 `${revision}` 属性（当前 `2.0.1-SNAPSHOT`），配合 `flatten-maven-plugin` 在 `process-resources` 阶段生成扁平化 POM，使 SNAPSHOT/Release 版本在仓库中可见且不含变量。
- **依赖治理**：所有第三方依赖仅出现在 `agentscope-dependencies-bom` 的 `<dependencyManagement>` 中，业务模块只声明 groupId/artifactId，不写 version，避免版本漂移。
- **模块化边界**：
  - `core`：框架核心运行时；
  - `extensions`：按能力拆分的可选扩展（模型、存储、沙箱、渠道、调度、Skill 仓库等）；
  - `distribution`：对外发布物（all jar + bom）；
  - `examples`：示例应用不参与 release 发布（在 release profile 中被 excludeArtifacts 排除）。
- **构建质量门禁**：
  - Spotless + Google Java Format (AOSP) 在 compile 阶段执行 `check`，禁止通配符导入并自动清理 unused import；
  - Surefire 开启 Jacoco agent，输出 plain 格式并在 test 阶段生成报告；
  - Javadoc 在 package 阶段生成源码与文档 jar。
- **发布流程**：`mvn -P release clean deploy` 触发 GPG 签名 → Sonatype Staging → Central Publishing，snapshot 走 `sonatype-nexus-snapshots`，release 走 `sonatype-nexus-staging`。
- **CI 特性**：使用 `mvnd`（Maven Daemon）1.0.3 加速并行构建，缓存 `~/.m2/repository` 与 `~/.mvnd`；Linux 强制 `-T1` 保证 reactor 顺序，避免子模块在未安装 core 时解析失败。

## 4. 开发者应遵循的规则
1. **新增扩展模块**：必须在 `agentscope-dependencies-bom` 中声明其依赖版本，并在 `agentscope-distribution/agentscope-all` 与 `agentscope-bom` 中添加对应 optional 依赖，否则 CI 的 `check-shade-and-bom-sync.sh` 会失败。
2. **不要自行指定依赖版本**：所有第三方依赖应从 BOM 继承，避免版本冲突。
3. **提交前本地运行 `mvn spotless:check`**：Spotless 在编译期也会执行，但本地提前修复可节省 CI 时间。
4. **修改版本号**：仅在根 `pom.xml` 的 `<revision>` 中调整，由 flatten 插件统一传播到所有子模块。
5. **发布 Release**：需准备 GPG 私钥并配置 `settings.xml` 中的 `sonatype-nexus-staging` 与 `central` server，然后执行 `mvn -P release clean deploy`。
6. **本地调试多模块**：建议先 `mvn install -pl agentscope-core -am` 安装 core，再 build 其他依赖它的模块，以避免 reactor 顺序问题。