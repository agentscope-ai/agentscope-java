# Structured Output

Structured output enables Agents to generate typed data conforming to predefined schemas, achieving reliable conversion from natural language to structured data.

---

## Quick Start

### 1. Define Schema

```java
public class ProductInfo {
    public String name;
    public Double price;
    public List<String> features;

    public ProductInfo() {}  // Must have no-arg constructor
}
```

### 2. Request Structured Output

```java
// Send query with output type
Msg response = agent.call(userMsg, ProductInfo.class).block();

// Extract typed data
ProductInfo data = response.getStructuredData(ProductInfo.class);

System.out.println("Product: " + data.name);
System.out.println("Price: $" + data.price);
```

---

## Two Modes

| Mode | Features | Use Case |
|------|----------|----------|
| `TOOL_CHOICE` (default) | Forces tool call, single API call | Models supporting tool_choice (qwen3-max, gpt-4) |
| `PROMPT` | Prompt-guided, may require multiple calls | Compatible with older models |

```java
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)  // or PROMPT
    .build();
```

---

## Schema Definition

### Supported Types

```java
public class Schema {
    // Basic types
    public String name;
    public Integer count;
    public Double score;
    public Boolean active;

    // Collection types
    public List<String> tags;
    public Map<String, Object> metadata;

    // Nested objects
    public Address address;
}
```

### Nested Structures

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

### Jackson Annotations

```java
public class CustomSchema {
    @JsonProperty("product_name")  // Custom field name
    public String productName;

    @JsonIgnore  // Ignore field
    public String internal;
}
```

---

## Error Handling

```java
try {
    Msg response = agent.call(userMsg, ProductInfo.class).block();
    ProductInfo data = response.getStructuredData(ProductInfo.class);

    // Business validation
    if (data.price < 0) {
        throw new IllegalArgumentException("Invalid price");
    }
} catch (Exception e) {
    System.err.println("Processing failed: " + e.getMessage());
}
```

---

## More Resources

- **Complete Example**: [StructuredOutputExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/StructuredOutputExample.java)
- **Agent Configuration**: [agent-config.md](./agent-config.md)
