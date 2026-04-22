# 结构化输出（Structured Output）

结构化输出让 Agent 生成符合预定义 Schema 的类型化数据，实现从自然语言到结构化数据的可靠转换。

---

## 快速开始

### 1. 定义 Schema

```java
public class ProductInfo {
    public String name;
    public Double price;
    public List<String> features;

    public ProductInfo() {}  // 必须有无参构造函数
}
```

### 2. 请求结构化输出

```java
// 发送查询，指定输出类型
Msg response = agent.call(userMsg, ProductInfo.class).block();

// 提取类型化数据
ProductInfo data = response.getStructuredData(ProductInfo.class);

System.out.println("产品: " + data.name);
System.out.println("价格: $" + data.price);
```

---

## 两种模式

| 模式 | 特点 | 适用场景 |
|------|------|---------|
| `TOOL_CHOICE`（默认） | 强制调用工具，一次 API 调用 | 支持 tool_choice 的模型（qwen3-max, gpt-4） |
| `PROMPT` | 提示词引导，可能多次调用 | 兼容老模型 |

```java
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)  // 或 PROMPT
    .build();
```

---

## Schema 定义

### 支持的类型

```java
public class Schema {
    // 基础类型
    public String name;
    public Integer count;
    public Double score;
    public Boolean active;

    // 集合类型
    public List<String> tags;
    public Map<String, Object> metadata;

    // 嵌套对象
    public Address address;
}
```

### 嵌套结构

```java
public class Person {
    public String name;
    public Address address;
    public List<String> hobbies;
}

public class Address {
    public String city;
    public String street;
}
```

### Jackson 注解

```java
public class CustomSchema {
    @JsonProperty("product_name")  // 自定义字段名
    public String productName;

    @JsonIgnore  // 忽略字段
    public String internal;
}
```

---

## 错误处理

```java
try {
    Msg response = agent.call(userMsg, ProductInfo.class).block();
    ProductInfo data = response.getStructuredData(ProductInfo.class);

    // 业务验证
    if (data.price < 0) {
        throw new IllegalArgumentException("价格无效");
    }
} catch (Exception e) {
    System.err.println("处理失败: " + e.getMessage());
}
```

---

## 更多资源

- **完整示例**: [StructuredOutputExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/StructuredOutputExample.java)
- **Agent 配置**: [agent-config.md](./agent-config.md)
