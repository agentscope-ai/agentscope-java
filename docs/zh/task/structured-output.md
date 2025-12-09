# 结构化输出（Structured Output）

结构化输出功能让 Agent 生成符合预定义 Schema 的类型化数据，而不仅仅是自由文本，实现从自然语言到结构化数据的可靠转换。

---

## 核心特性

- **类型安全**：使用 Java Class 定义输出结构
- **自动 Schema**：自动从 Java Class 生成 JSON Schema
- **自动验证**：确保输出符合预期格式
- **两种模式**：TOOL_CHOICE（强制）和 PROMPT（兼容）
- **优雅处理**：自动清理中间历史，只保留最终结果

---

## 快速开始

### 1. 定义输出 Schema

```java
import java.util.List;

// 使用简单的 Java 类定义结构
public class ProductInfo {
    public String name;
    public Double price;
    public List<String> features;
    
    public ProductInfo() {}  // 必须有无参构造函数
}
```

### 2. 请求结构化输出

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

// 发送查询
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder()
        .text("提取产品信息：iPhone 15 Pro，价格 $999，支持 5G")
        .build())
    .build();

// 请求结构化输出
Msg response = agent.call(userMsg, ProductInfo.class).block();

// 提取类型化数据
ProductInfo data = response.getStructuredData(ProductInfo.class);

System.out.println("产品名称: " + data.name);
System.out.println("价格: $" + data.price);
System.out.println("特性: " + data.features);
```

**输出示例**：
```
产品名称: iPhone 15 Pro
价格: $999.0
特性: [5G, ...]
```


###  TOOL_CHOICE 与 PROMPT

#### TOOL_CHOICE（推荐，默认）

```java
import io.agentscope.core.model.StructuredOutputReminder;

ReActAgent agent = ReActAgent.builder()
    .name("AnalysisAgent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)  // 默认值
    .build();
```

**特点**：
- 使用 `toolChoice: specific("generate_response")` 强制调用
- 可靠性高，一次性完成，模型必须调用工具
- 效率高，只需要一次 API 调用
- 需要模型支持 tool_choice 参数（如 qwen-max, gpt-4）

#### PROMPT（兼容模式）

```java
ReActAgent agent = ReActAgent.builder()
    .name("AnalysisAgent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.PROMPT)
    .build();
```

**特点**：
- 依赖提示词引导模型调用工具
- 如果模型未调用，自动添加提醒消息并重试
- 兼容性更好，适用于不支持 tool_choice 的老模型
- 可能需要多次 API 调用，成本较高

---

## Schema 定义

### 基础类型

```java
public class SimpleSchema {
    public String name;        // 字符串
    public Integer age;        // 整数
    public Double score;       // 浮点数
    public Boolean active;     // 布尔值
}
```

### 集合类型

```java
public class CollectionSchema {
    public List<String> tags;           // 字符串列表
    public List<Integer> numbers;       // 整数列表
    public Map<String, Object> metadata; // 键值对
}
```

### 嵌套对象

```java
public class Address {
    public String street;
    public String city;
    public String zipCode;
}

public class Person {
    public String name;
    public int age;
    public Address address;  // 嵌套对象
    public List<String> hobbies;
}
```

### 可选字段

```java
public class OptionalFields {
    public String required;        // 必需（非 null）
    public String optional;        // 可选（可以为 null）
    public Integer count = 0;      // 带默认值
}
```

---


## 完整示例

以下是三个实际应用场景的完整示例，展示结构化输出的不同用法。

### 示例 1：产品需求提取

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class ProductAnalysisExample {
    
    // 定义产品需求结构
    public static class ProductRequirements {
        public String productType;
        public String brand;
        public Integer minRam;
        public Double maxBudget;
        public List<String> features;
        
        public ProductRequirements() {}
    }
    
    public static void main(String[] args) {
        // 创建 Agent
        ReActAgent agent = ReActAgent.builder()
            .name("AnalysisAgent")
            .sysPrompt("You are an intelligent analysis assistant. "
                + "Analyze user requests and provide structured responses.")
            .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-max")
                .stream(true)
                .enableThinking(false)
                .build())
            .toolkit(new Toolkit())
            .memory(new InMemoryMemory())
            .build();
        
        // 准备查询
        String query = "I'm looking for a laptop. I need at least 16GB RAM, "
            + "prefer Apple brand, and my budget is around $2000. "
            + "It should be lightweight for travel.";
        
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder()
                .text("Extract the product requirements from this query: " + query)
                .build())
            .build();
        
        try {
            // 获取结构化输出
            Msg response = agent.call(userMsg, ProductRequirements.class).block();
            ProductRequirements result = response.getStructuredData(ProductRequirements.class);
            
            // 打印提取的数据
            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

**输出示例**：
```
Extracted structured data:
  Product Type: laptop
  Brand: Apple
  Min RAM: 16 GB
  Max Budget: $2000.0
  Features: [lightweight, travel-friendly]
```

### 示例 2：联系信息提取

```java
// 定义联系信息结构
public static class ContactInfo {
    public String name;
    public String email;
    public String phone;
    public String company;
    
    public ContactInfo() {}
}

// 使用示例
String text = "Please contact John Smith at john.smith@example.com or "
    + "call him at +1-555-123-4567. His company is TechCorp Inc.";

Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder()
        .text("Extract contact information from: " + text)
        .build())
    .build();

Msg response = agent.call(userMsg, ContactInfo.class).block();
ContactInfo result = response.getStructuredData(ContactInfo.class);

System.out.println("Extracted contact information:");
System.out.println("  Name: " + result.name);
System.out.println("  Email: " + result.email);
System.out.println("  Phone: " + result.phone);
System.out.println("  Company: " + result.company);
```

**输出示例**：
```
Extracted contact information:
  Name: John Smith
  Email: john.smith@example.com
  Phone: +1-555-123-4567
  Company: TechCorp Inc.
```

### 示例 3：情感分析

```java
// 定义情感分析结构
public static class SentimentAnalysis {
    public String overallSentiment;  // "positive", "negative", or "neutral"
    public Double positiveScore;     // 0.0 to 1.0
    public Double negativeScore;     // 0.0 to 1.0
    public Double neutralScore;      // 0.0 to 1.0
    public List<String> keyTopics;
    public String summary;
    
    public SentimentAnalysis() {}
}

// 使用示例
String review = "This product exceeded my expectations! The quality is amazing "
    + "and the customer service was very helpful. However, "
    + "the shipping took a bit longer than expected.";

Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder()
        .text("Analyze the sentiment of this review and provide scores: " + review)
        .build())
    .build();

Msg response = agent.call(userMsg, SentimentAnalysis.class).block();
SentimentAnalysis result = response.getStructuredData(SentimentAnalysis.class);

System.out.println("Sentiment analysis results:");
System.out.println("  Overall Sentiment: " + result.overallSentiment);
System.out.println("  Positive Score: " + result.positiveScore);
System.out.println("  Negative Score: " + result.negativeScore);
System.out.println("  Neutral Score: " + result.neutralScore);
System.out.println("  Key Topics: " + result.keyTopics);
System.out.println("  Summary: " + result.summary);
```

**输出示例**：
```
Sentiment analysis results:
  Overall Sentiment: positive
  Positive Score: 0.75
  Negative Score: 0.15
  Neutral Score: 0.10
  Key Topics: [quality, customer service, shipping]
  Summary: Mostly positive with minor concerns about delivery time
```

---

## 高级用法

### 1. 复杂嵌套结构

```java
public class AnalysisReport {
    public Summary summary;
    public List<Finding> findings;
    public Recommendation recommendation;
    
    public static class Summary {
        public String overview;
        public int totalIssues;
    }
    
    public static class Finding {
        public String issue;
        public String severity;  // "high" / "medium" / "low"
        public String location;
    }
    
    public static class Recommendation {
        public List<String> actions;
        public int priority;
    }
}
```

### 2. 验证和错误处理

```java
try {
    Msg response = agent.call(userMsg, ProductInfo.class).block();
    ProductInfo data = response.getStructuredData(ProductInfo.class);
    
    // 业务验证
    if (data.price == null || data.price < 0) {
        System.err.println("价格无效");
    }
    
} catch (IllegalArgumentException e) {
    System.err.println("Schema 转换失败: " + e.getMessage());
} catch (Exception e) {
    System.err.println("处理失败: " + e.getMessage());
}
```

### 3. 条件结构化输出

```java
// 动态决定是否需要结构化输出
Class<?> schema = needsStructuredOutput ? ProductInfo.class : null;

Msg response = schema != null 
    ? agent.call(userMsg, schema).block()
    : agent.call(userMsg).block();
```

### 4. 多步骤结构化

```java
// 第一步：提取实体
Msg step1 = agent.call(userMsg, EntityList.class).block();
EntityList entities = step1.getStructuredData(EntityList.class);

// 第二步：分析关系
Msg step2 = agent.call(
    Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder()
            .text("分析这些实体之间的关系：" + entities)
            .build())
        .build(),
    RelationshipGraph.class
).block();
```


## 高级特性

### 1. 自定义验证

```java
Msg response = agent.call(userMsg, ProductInfo.class).block();
ProductInfo data = response.getStructuredData(ProductInfo.class);

// 自定义业务验证
if (data.price != null && data.price < 0) {
    throw new IllegalArgumentException("价格不能为负数");
}

if (data.features == null || data.features.isEmpty()) {
    throw new IllegalArgumentException("必须至少有一个特性");
}
```

### 2. 使用 Jackson 注解

```java
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class CustomSchema {
    @JsonProperty("product_name")  // 自定义 JSON 字段名
    public String productName;
    
    @JsonIgnore  // 忽略此字段
    public transient String internalCache;
}
```

### 3. 泛型支持

```java
public class Response<T> {
    public boolean success;
    public String message;
    public T data;
}

// 使用时指定具体类型
// 注意：泛型擦除限制，需要创建具体类
public class UserResponse extends Response<User> {}
```



---

## 更多资源

- **完整示例代码**: [StructuredOutputExample.java](../../agentscope-examples/quickstart/src/main/java/io/agentscope/examples/StructuredOutputExample.java)
- **Agent 配置**: [agent-config.md](./agent-config.md) - 了解 StructuredOutputReminder 配置
- **Model 文档**: [model.md](./model.md) - 了解模型配置
- **Tool 文档**: [tool.md](./tool.md) - 了解工具机制原理

