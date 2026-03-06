# AgentScope Tool Argument Parser
**健壮的工具调用参数解析器**，用于修复LLM输出的各种JSON格式问题。

## 🎯 核心特性

### 工具参数解析器 (ToolArgumentParser)
- ✅ **5阶段渐进式清理策略**：从标准JSON到完整修复
- ✅ **Jackson Lenient Mode**：支持注释、单引号、非引号字段名
- ✅ **ReDoS防护**：所有正则表达式都有大小限制
- ✅ **智能括号计数**：跳过字符串内容，避免误判
- ✅ **安全限制**：最大参数大小100KB，防止DoS攻击

### Hook 集成 (ToolArgumentParserHook)
- ✅ **自动参数矫正**：通过 Hook 机制自动拦截和修正工具调用参数
- ✅ **一行代码集成**：手动注册即可使用
- ✅ **Content 字段处理**：直接处理 ToolUseBlock.content 字符串

## 📦 快速开始

### Maven依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-tool-parser</artifactId>
</dependency>
```

### 基本使用

```java
import io.agentscope.core.tool.parser.ToolArgumentParser;
import io.agentscope.core.tool.parser.ParseResult;

// 解析工具参数
String rawJson = "{\"query\":\"test\", \"limit\":10}";
ParseResult result = ToolArgumentParser.parse(rawJson, "searchTool");

if (result.isSuccess()) {
    // 使用解析后的JSON
    Map<String, Object> args = objectMapper.readValue(
        result.parsedArguments(),
        new TypeReference<Map<String, Object>>() {}
    );
} else {
    // 处理解析失败
    System.err.println("解析失败: " + result.errorMessage());
}
```

## 🔧 Hook 集成（推荐）

### 手动注册（推荐）

```java
import io.agentscope.core.tool.parser.ToolArgumentParserHook;

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hooks(List.of(
        new ToolArgumentParserHook()  // 自动修正工具参数
    ))
    .build();
```

### Hook 工作原理

1. **事件拦截**：Hook 拦截 `PreActingEvent`（工具执行前的事件）
2. **内容提取**：从 `ToolUseBlock.content` 提取原始 JSON 字符串
3. **自动修正**：应用 5 阶段清理策略修复格式问题
4. **内容更新**：将修正后的 JSON 更新回 `ToolUseBlock.content`
5. **元数据保留**：保留 `ToolUseBlock` 的 id、name、input、metadata 等字段

## 🔧 解析阶段

工具参数解析器采用**多阶段渐进式清理策略**：

| 阶段 | 说明 | 示例 |
|------|------|------|
| **Stage 0: DIRECT** | 标准JSON，直接解析 | `{"key":"value"}` |
| **Stage 1: MARKDOWN_CLEAN** | 移除```json代码块 | ```json<br>{"key":"value"}<br>``` |
| **Stage 2: COMMENT_STRIP** | 移除//和/* */注释 | `{"key":"value" // comment}` |
| **Stage 3: QUOTE_FIX** | 单引号转双引号（Jackson自动处理） | `{'key':'value'}` |
| **Stage 4: JSON_REPAIR** | 修复缺失括号和尾随逗号 | `{"key":"value",` |
| **ORIGINAL** | 所有阶段失败，返回原始输入 | - |

## 📝 支持的格式

解析器可以处理LLM输出的各种格式问题：

### 1. 标准JSON ✅
```json
{"query":"test","limit":10}
```

### 2. Markdown代码块 ✅
```
```json
{"query":"test"}
```
```

### 3. 带注释的JSON ✅
```json
{"query":"test", // 搜索关键词
 "limit":10 /* 最大结果数 */}
```

### 4. 单引号JSON ✅
```json
{'query':'test','limit':10}
```

### 5. 非引号字段名 ✅
```json
{query:"test",limit:10}
```

### 6. 尾随逗号 ✅
```json
{"query":"test",}
```

### 7. 缺失括号 ✅
```json
{"data":{"items":[1,2  // 缺失的括号会被自动修复
```

## 🛡️ 安全特性

### ReDoS防护
- **代码块大小限制**：50KB
- **注释块大小限制**：10KB
- **总参数大小限制**：100KB

### 输入验证
- null和空输入会返回ParseResult.failure()
- 超过大小限制的输入会被拒绝
- 清晰的错误消息帮助排查问题

## 📊 性能特性

### Jackson优化
使用Jackson的lenient模式特性：
- `ALLOW_COMMENTS`：支持//和/* */注释
- `ALLOW_SINGLE_QUOTES`：支持单引号字符串
- `ALLOW_UNQUOTED_FIELD_NAMES`：支持非引号字段名
- `ALLOW_UNQUOTED_CONTROL_CHARS`：支持非转义控制字符

## 🔍 API参考

### ToolArgumentParser

主要解析入口类。

#### 方法
```java
public static ParseResult parse(String rawArguments, String toolName)
```

**参数**：
- `rawArguments` - 原始JSON字符串
- `toolName` - 工具名称（用于错误消息）

**返回**：
- `ParseResult` - 解析结果对象

### ParseResult

解析结果记录类。

#### 字段
```java
public record ParseResult(
    String parsedArguments,  // 解析后的JSON字符串
    ParseStage stage,        // 达到的解析阶段
    String errorMessage      // 错误消息（失败时）
)
```

#### 方法
```java
boolean isSuccess()                    // 是否解析成功
boolean isDirectSuccess()              // 是否直接解析成功（无清理）
boolean requiredMultipleStages()       // 是否需要多阶段清理
```

## 🧪 测试

运行测试：

```bash
mvn clean test
```

### 测试分类

| 测试类别 | 测试数量 | 说明 |
|---------|---------|------|
| **基础功能测试** | 2 | Hook 创建、优先级验证 |
| **PreActingEvent 处理测试** | 5 | 标准JSON、Markdown、注释、单引号、尾随逗号 |
| **错误处理测试** | 3 | 无效JSON、null content、空content |
| **事件类型过滤测试** | 2 | PostActingEvent 过滤、PreActingEvent 处理 |
| **特殊内容测试** | 4 | Unicode、嵌套结构、数组、混合类型 |
| **Reactive 编程测试** | 2 | Mono 响应式流、顺序处理 |
| **ToolUseBlock 更新测试** | 2 | 元数据保留、字段更新 |

## 📚 使用示例

### 示例1：基本解析

```java
String json = "{\"query\":\"test\",\"limit\":10}";
ParseResult result = ToolArgumentParser.parse(json, "searchTool");

if (result.isSuccess()) {
    System.out.println("解析成功: " + result.parsedArguments());
    System.out.println("阶段: " + result.stage());
}
```

### 示例2：处理Markdown代码块

```java
String markdown = """
```json
{"query":"test","limit":10}
```
""";

ParseResult result = ToolArgumentParser.parse(markdown, "searchTool");
// 自动移除```json```，解析成功
```

### 示例3：错误处理

```java
String invalid = "definitely not json";
ParseResult result = ToolArgumentParser.parse(invalid, "testTool");

if (!result.isSuccess()) {
    System.err.println("解析失败: " + result.errorMessage());
    // stage == ParseStage.ORIGINAL
}
```

### 示例4：Hook集成

```java
// 创建Hook并注册到Agent
ToolArgumentParserHook hook = new ToolArgumentParserHook();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hooks(List.of(hook))
    .build();

// Agent执行时，Hook会自动修正工具参数的content字段
AgentResponse response = agent.run("帮我搜索最新AI新闻");
```

## 🚧 高级配置

### 自定义ObjectMapper

如果需要自定义Jackson配置：

```java
public class CustomToolArgumentParser {
    private static final ObjectMapper customMapper = new ObjectMapper()
        .enable(JsonParser.Feature.ALLOW_COMMENTS)
        .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 使用customMapper而不是默认的ObjectMapper
}
```

### 大小限制调整

如需修改大小限制，可以修改常量：

```java
// 在ToolArgumentParser中
private static final int MAX_ARGUMENT_SIZE = 200_000;  // 增加到200KB
private static final int MAX_CODE_BLOCK_SIZE = 100_000; // 增加到100KB
```

## 📖 最佳实践

### 1. 推荐使用方式

```java
// 推荐：直接创建 Hook，自动修正参数
ToolArgumentParserHook hook = new ToolArgumentParserHook();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hooks(List.of(hook))
    .build();
```

**优势**：
- ✅ 自动修正 LLM 输出的各种 JSON 格式问题
- ✅ 保留原始 ToolUseBlock 的元数据
- ✅ 不修改 input Map，只更新 content 字段
- ✅ 错误时返回原始事件，不影响正常流程

### 2. Hook 优先级配置

```java
// 如果有多个 Hook，可以通过 priority() 控制执行顺序
ToolArgumentParserHook parserHook = new ToolArgumentParserHook();

// 自定义 Hook 优先级
public class CustomHook implements Hook {
    @Override
    public int priority() {
        return 90;  // 小于 100 会在 parserHook 之前执行
    }

    // ... 其他方法
}
```
