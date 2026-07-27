---
kind: build_system
name: Maven 多模块构建与发布体系
slug: build_system
category: build_system
scope:
    - '**'
---

## 构建系统概览

AgentScope Java 采用 **Maven 聚合工程 + BOM 依赖管理** 的构建体系，通过 `agentscope-parent` 作为根 POM 统一管理所有子模块的版本、插件和构建配置。项目使用 Maven Enforcer/Spotless 进行代码质量检查，GitHub Actions 执行 CI 流水线，并通过 Sonatype Central Publishing 发布到 Maven Central。

## 核心架构决策

### 1. 版本管理策略
- 使用 `${revision}` 属性配合 `flatten-maven-plugin` 实现统一版本号管理
- 根 POM 定义 `2.0.1-SNAPSHOT` 作为当前开发版本
- 通过 `maven-enforcer-plugin` 确保所有模块版本一致

### 2. 模块化结构
```xml
<modules>
    <module>agentscope-core</module>        # 核心运行时
    <module>agentscope-harness</module>     # 测试框架
    <module>agentscope-extensions</module>   # 扩展生态
    <module>agentscope-examples</module>     # 示例应用
    <module>agentscope-dependencies-bom</module> # 依赖BOM
    <module>agentscope-distribution</module>   # 分发包
</modules>
```

### 3. 依赖管理分层
- **agentscope-dependencies-bom**: 集中管理第三方库版本（Spring Boot 4.0.4, Reactor 2025.0.2 等）
- **agentscope-bom**: 管理 AgentScope 内部模块间的依赖关系
- 各子模块仅声明需要的依赖，版本由父 POM 统一管理

### 4. 构建插件配置
- **Spotless**: Google Java Format (AOSP风格) + 导入排序 + 自动清理
- **Surefire**: JUnit 5 测试执行，集成 JaCoCo 覆盖率统计
- **Javadoc**: 生成源码文档，支持依赖源码包含
- **Source**: 自动生成源码包
- **GPG**: release profile 下对发布包进行数字签名

### 5. 打包策略
- **agentscope-all**: 使用 maven-shade-plugin 将所有可选依赖打包成单一 jar
- 所有扩展模块标记为 `<optional>true</optional>`，按需引入
- 支持 Spring Boot Starter 模式的自动装配

## CI/CD 流水线

### GitHub Actions 工作流
- **maven-ci.yml**: 在 Ubuntu 和 Windows 双平台执行构建和测试
- 使用 Maven Daemon (`mvnd`) 加速构建
- 集成 Codecov 上传覆盖率报告
- 许可证检查通过 Apache SkyWalking Eyes
- 模块同步检查确保 BOM 与实际模块保持一致

### 本地开发命令
```bash
# 完整构建（编译+测试+打包）
mvn clean verify

# 跳过测试快速构建
mvn clean package -DskipTests

# 运行单个模块测试
mvn test -pl agentscope-core

# 生成站点文档
mvn site
```

## 发布流程

### Snapshot 发布
```bash
mvn deploy -Dgpg.skip=true
```

### Release 发布
```bash
# 更新版本号并打标签
mvn versions:set -DnewVersion=2.0.1
mvn clean deploy -P release
```

发布目标：Sonatype Nexus Staging Repository → Maven Central

## 开发者规范

### 新增模块要求
1. 继承 `agentscope-parent` 或 `agentscope-distribution` 父 POM
2. 在对应聚合 POM 中注册新模块
3. 如需对外暴露 API，需添加到 `agentscope-bom` 中
4. 遵循统一的编码规范和注释标准

### 依赖管理原则
- 优先使用 BOM 中已定义的版本
- 避免在子模块中硬编码版本号
- 第三方依赖尽量使用官方 BOM 管理
- 保持传递依赖的最小化

### 构建优化建议
- 合理使用 `mvn -T1` 单线程构建保证 reactor 顺序
- 利用 Maven 缓存机制加速重复构建
- 大型项目建议使用 Maven Daemon 提升性能