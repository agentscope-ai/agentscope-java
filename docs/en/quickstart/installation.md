# Installation

AgentScope Java requires **JDK 17 or higher**. You can install the library through Maven or build from source.

## Maven Dependency

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>0.2.1</version>
</dependency>
```

For Gradle users, add this to your `build.gradle`:

```gradle
implementation 'io.agentscope:agentscope-core:0.2.1'
```

## Build from Source

To build AgentScope Java from source, clone the repository and install locally:

```bash
git clone https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
mvn clean install
```
