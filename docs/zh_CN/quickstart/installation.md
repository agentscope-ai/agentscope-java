# 安装

AgentScope Java 需要 **JDK 17 或更高版本**。您可以通过 Maven 安装或从源代码构建。

## Maven 依赖

在您的 `pom.xml` 中添加以下依赖：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

Gradle 用户可以在 `build.gradle` 中添加：

```gradle
implementation 'io.agentscope:agentscope-core:0.2.0'
```

## 从源代码构建

从源代码构建 AgentScope Java，需要克隆仓库并在本地安装：

```bash
git clone https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
mvn clean install
```
