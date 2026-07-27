---
kind: dependency_management
name: Maven 多模块依赖治理与 BOM 分发体系
slug: dependency_management
category: dependency_management
scope:
    - '**'
---

AgentScope Java 采用 Maven 多模块聚合工程，通过「父 POM + 双 BOM + All-in-One」三层结构实现统一的第三方依赖版本治理与对外分发。

## 1. 版本治理架构
- 父 POM（agentscope-parent）：定义全局属性（revision=2.0.0-SNAPSHOT、java.version=17）、插件版本及测试依赖，并通过 dependencyManagement 引入两个 BOM：
  - agentscope-dependencies-bom：集中声明所有第三方库的版本号（如 Reactor 2025.0.2、Jackson 2.21.1、Spring Boot 4.0.4、OpenAI SDK 4.28.0、Anthropic 2.14.0、DashScope 2.22.9、OkHttp 5.3.2、Reactor BOM、Junit BOM、Mockito BOM、Kubernetes Client BOM 等）。
  - agentscope-bom：声明 AgentScope 自身各子模块的坐标与版本号。
- dependencies-bom（agentscope-dependencies-bom）：纯第三方依赖版本中心，使用 properties 集中管理 60+ 个外部库版本，并 import 主流生态 BOM（opentelemetry-bom、okhttp-bom、reactor-bom、jackson-bom、mockito-bom、junit-bom、kubernetes-client-bom），避免传递性冲突。
- 项目 BOM（agentscope-bom）：面向消费者，列出所有可发布的 io.agentscope:* 工件（core、harness、extensions-*、spring-boot-starters 等），统一 ${project.version}，供下游项目通过 dependencyManagement import 引入。
- All-in-One 包（agentscope-all → agentscope）：使用 maven-shade-plugin 将 core 与所有 extensions-* 打包为单一 jar，所有扩展依赖标记为 optional=true，由使用者按需引入；同时显式声明 slf4j-api、reactor-core、jackson-databind、jsonschema-generator 等核心运行时依赖。

## 2. 发布与仓库策略
- 通过 flatten-maven-plugin（flattenMode=oss / bom）在构建期生成扁平化 POM，移除 CI/本地变量，确保发布到中央仓库的 POM 干净可复现。
- distributionManagement 指向 Sonatype OSSRH（staging + snapshots），配合 central-publishing-maven-plugin 与 GPG 签名（release profile）完成 Maven Central 发布。
- 根 POM 的 release profile 中通过 excludeArtifacts 明确排除示例与应用模块，仅发布框架工件。

## 3. 自动化升级
- .github/dependabot.yaml 配置每周日 03:00（Asia/Shanghai）对根目录执行 Maven 生态扫描，自动创建 PR 更新依赖版本，上限 20 个并发 PR。

## 4. 开发者规范
- 新增第三方依赖时，必须在 agentscope-dependencies-bom/pom.xml 的 properties 中声明版本号，并在 dependencyManagement 中注册，禁止在业务模块直接写死版本。
- 新增 AgentScope 子模块后，需在 agentscope-bom/pom.xml 中登记其坐标，以便对外发布。
- 扩展模块依赖应尽可能标记 optional=true，由使用者按需引入，保持最小依赖面。
- 使用 mvn versions:display-dependency-updates 或 Dependabot 定期审查版本漂移。
- 如需引入新的第三方 BOM，应在 dependencies-bom 中 import，而非在各模块重复声明。