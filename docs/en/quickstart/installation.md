# Installation

AgentScope Java requires **JDK 17 or higher**. You can install the library through Maven or build from source.

## Maven Dependency

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

For Gradle users, add this to your `build.gradle`:

```gradle
implementation 'io.agentscope:agentscope-core:0.2.0'
```

## Build from Source

To build AgentScope Java from source, clone the repository and install locally:

```bash
git clone https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
mvn clean install
```

## Verify Installation

Create a simple Java class to verify the installation:

```java
package io.agentscope.tutorial.quickstart;

import io.agentscope.core.Version;

public class VerifyInstallation {
    public static void main(String[] args) {
        System.out.println("AgentScope Version: " + Version.VERSION);
        System.out.println("User-Agent: " + Version.getUserAgent());
    }
}
```

Run the program:

```bash
mvn compile exec:java -Dexec.mainClass="io.agentscope.tutorial.quickstart.VerifyInstallation"
```

Expected output:

```
AgentScope Version: 0.1.0
User-Agent: agentscope-java/0.1.0; java/17.0.1; platform/Mac OS X
```

> **Note**: The Maven artifact version (0.2.0) may differ from the internal version constant (0.1.0) displayed at runtime. This is normal during development.

## System Requirements

- **JDK**: 17 or higher
- **Build Tool**: Maven 3.6+ or Gradle 7.0+
- **Operating System**: Windows, macOS, or Linux

## IDE Setup

### IntelliJ IDEA

1. Open the project or create a new Maven project
2. Add the dependency to `pom.xml`
3. Reload Maven project (right-click on `pom.xml` → Maven → Reload Project)

### Eclipse

1. Create a new Maven project
2. Add the dependency to `pom.xml`
3. Update Maven project (right-click on project → Maven → Update Project)

### VS Code

1. Install the "Java Extension Pack"
2. Open the project folder
3. Add the dependency to `pom.xml`
4. Maven will automatically download dependencies

## Development Dependencies

For development and testing, you may also need:

```xml
<!-- Testing -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>

<!-- Reactive Streams Testing -->
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <version>3.6.0</version>
    <scope>test</scope>
</dependency>
```

## Model API Keys

AgentScope Java supports various LLM providers. You need to configure API keys for the models you plan to use:

- **DashScope (Alibaba Cloud)**: Set `DASHSCOPE_API_KEY` environment variable
- **OpenAI**: Set `OPENAI_API_KEY` environment variable

Example:

```bash
# Linux/macOS
export DASHSCOPE_API_KEY="your-api-key"
export OPENAI_API_KEY="your-api-key"

# Windows (Command Prompt)
set DASHSCOPE_API_KEY=your-api-key
set OPENAI_API_KEY=your-api-key

# Windows (PowerShell)
$env:DASHSCOPE_API_KEY="your-api-key"
$env:OPENAI_API_KEY="your-api-key"
```

## Code Formatting

AgentScope Java uses **Spotless** with Google Java Format (AOSP style). To format your code:

```bash
# Apply formatting
mvn spotless:apply

# Check formatting
mvn spotless:check
```

**Important**: Always run `mvn spotless:apply` after modifying code. Compilation will fail without proper formatting.

## Next Steps

- [Key Concepts](key-concepts.md) - Understand core concepts in AgentScope
- [Message](message.md) - Learn about the message system
- [Agent](agent.md) - Build your first agent

## Troubleshooting

### JDK Version Mismatch

If you encounter errors about unsupported Java version:

```bash
# Check your Java version
java -version

# Should be 17 or higher
javac -version
```

Make sure both `java` and `javac` are pointing to JDK 17+.

### Maven Compilation Fails

If Maven compilation fails with formatting errors:

```bash
# Run Spotless to fix formatting issues
mvn spotless:apply

# Then compile again
mvn compile
```

### Dependency Download Issues

If dependencies fail to download:

```bash
# Clear Maven cache and retry
rm -rf ~/.m2/repository/io/agentscope
mvn clean install -U
```
