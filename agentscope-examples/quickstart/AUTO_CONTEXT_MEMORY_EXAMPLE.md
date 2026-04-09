# AutoContextMemory Example - 大消息压缩测试

这个示例程序演示了 AutoContextMemory 的自动大消息压缩功能，包括工具调用消息的格式转换。

## 功能特性

- ✅ 自动压缩超过阈值的大消息
- ✅ 工具调用消息（ToolUse/ToolResult）转换为文本格式，避免模型报错
- ✅ 上下文卸载（Offload）以节省上下文窗口
- ✅ 交互式聊天和自动演示两种模式

## 运行方式

### 1. 设置 API Key

方式一：设置环境变量
```bash
export DASHSCOPE_API_KEY="your-api-key-here"
```

方式二：运行程序时手动输入

### 2. 编译并运行

```bash
# 在项目根目录编译
cd /Users/nov11/github/agentscope-java
mvn clean install -DskipTests

# 进入 examples 目录
cd agentscope-examples/quickstart

# 运行示例
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.AutoContextMemoryExample"
```

或者直接使用 IDE 运行 `AutoContextMemoryExample.java`

## 程序说明

### 配置参数

程序使用以下配置来触发压缩（为了演示目的，阈值设置较低）：

- **消息阈值**: 8 条消息
- **大消息阈值**: 500 字符
- **保留最近消息**: 3 条
- **工具消息压缩**: 禁用（需要连续 6 条工具消息才压缩）

### 可用工具

程序注册了三个会生成大输出的工具：

1. **search_data**: 搜索信息并返回详细结果（约 1000+ 字符）
2. **analyze_data**: 执行数据分析并生成综合报告（约 1500+ 字符）
3. **fetch_document**: 检索完整文档内容（约 2000+ 字符）

### 运行模式

#### 模式 1: 交互式聊天
- 自由输入消息与 AI 对话
- 让 AI 使用工具来触发大消息压缩
- 输入 `status` 查看内存状态
- 输入 `quit` 或 `exit` 退出

#### 模式 2: 自动演示
- 自动发送 10 条预设消息
- 展示压缩触发过程
- 显示内存使用统计

## 观察要点

运行程序时，注意观察：

1. **压缩触发**: 当消息数超过 8 条时，会触发自动压缩
2. **工具消息转换**: 大型工具调用/结果消息会被转换为 ASSISTANT 角色的文本消息
3. **上下文卸载**: 原始消息被卸载到 offload context 中保存
4. **内存统计**: 
   - Working messages: 当前工作消息数
   - Original messages: 原始完整消息数
   - Offloaded contexts: 卸载的上下文数

## 测试场景

### 场景 1: 大工具结果消息
```
用户: 请搜索关于机器学习的信息
→ 触发 search_data 工具，返回 1000+ 字符结果
→ 消息被压缩并转换为文本格式
```

### 场景 2: 连续工具调用
```
用户: 分析这些数据
→ 触发 analyze_data 工具，返回 1500+ 字符报告
→ 超过阈值，触发压缩
```

### 场景 3: 多轮对话压缩
```
用户: [连续对话超过 8 轮]
→ 自动触发压缩策略
→ 历史消息被压缩和卸载
```

## 代码位置

- 示例程序: `AutoContextMemoryExample.java`
- 核心实现: `agentscope-extensions-autocontext-memory/src/main/java/.../AutoContextMemory.java`
- 转换方法: `convertToolMessageToText()` (第 648-726 行)

## 故障排除

### 问题: 编译错误
```
The import io.agentscope.core.memory.autocontext cannot be resolved
```
**解决**: 确保已编译 autocontext-memory 扩展模块
```bash
cd agentscope-extensions/agentscope-extensions-autocontext-memory
mvn clean install -DskipTests
```

### 问题: 运行时 API Key 错误
**解决**: 确保设置了有效的 DASHSCOPE_API_KEY 环境变量

### 问题: 压缩未触发
**解决**: 
- 检查消息数是否超过阈值（默认 8 条）
- 使用工具生成大输出（500+ 字符）
- 查看控制台日志了解压缩策略执行情况

## 更多信息

- AutoContextMemory 文档: 查看项目主文档
- 相关测试: `AutoContextMemoryTest.java`
- 压缩策略: 6 种渐进式压缩策略，从轻量到重量
