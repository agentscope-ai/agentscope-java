# Structured Output

Structured output functionality enables Agents to generate typed data conforming to predefined schemas, rather than just free text, achieving reliable conversion from natural language to structured data.

---

## Core Features

- **Type Safety**: Define output structure using Java classes
- **Automatic Schema**: Automatically generate JSON Schema from Java classes
- **Automatic Validation**: Ensure output conforms to expected format
- **Two Modes**: TOOL_CHOICE (forced) and PROMPT (compatible)
- **Graceful Handling**: Automatically clean up intermediate history, keeping only final results

---

## Quick Start

### 1. Define Output Schema

```java
import java.util.List;

// Define structure using a simple Java class
public class ProductInfo {
    public String name;
    public Double price;
    public List<String> features;
    
    public ProductInfo() {}  // Must have no-arg constructor
}
```

### 2. Request Structured Output

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

// Send query
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder()
        .text("Extract product information: iPhone 15 Pro, price $999, supports 5G")
        .build())
    .build();

// Request structured output
Msg response = agent.call(userMsg, ProductInfo.class).block();

// Extract typed data
ProductInfo data = response.getStructuredData(ProductInfo.class);

System.out.println("Product name: " + data.name);
System.out.println("Price: $" + data.price);
System.out.println("Features: " + data.features);
```

**Output Example**:
```
Product name: iPhone 15 Pro
Price: $999.0
Features: [5G, ...]
```


### TOOL_CHOICE vs PROMPT

#### TOOL_CHOICE (Recommended, Default)

```java
import io.agentscope.core.model.StructuredOutputReminder;

ReActAgent agent = ReActAgent.builder()
    .name("AnalysisAgent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)  // Default value
    .build();
```

**Features**:
- Uses `toolChoice: specific("generate_response")` to force call
- High reliability, one-time completion, model must call tool
- High efficiency, requires only one API call
- Requires model support for tool_choice parameter (e.g., qwen-max, gpt-4)

#### PROMPT (Compatible Mode)

```java
ReActAgent agent = ReActAgent.builder()
    .name("AnalysisAgent")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.PROMPT)
    .build();
```

**Features**:
- Relies on prompts to guide model to call tool
- If model doesn't call, automatically adds reminder message and retries
- Better compatibility, suitable for older models that don't support tool_choice
- May require multiple API calls, higher cost

---

## Schema Definition

### Basic Types

```java
public class SimpleSchema {
    public String name;        // String
    public Integer age;        // Integer
    public Double score;       // Double
    public Boolean active;     // Boolean
}
```

### Collection Types

```java
public class CollectionSchema {
    public List<String> tags;           // String list
    public List<Integer> numbers;       // Integer list
    public Map<String, Object> metadata; // Key-value pairs
}
```

### Nested Objects

```java
public class Address {
    public String street;
    public String city;
    public String zipCode;
}

public class Person {
    public String name;
    public int age;
    public Address address;  // Nested object
    public List<String> hobbies;
}
```

### Optional Fields

```java
public class OptionalFields {
    public String required;        // Required (non-null)
    public String optional;        // Optional (can be null)
    public Integer count = 0;      // With default value
}
```

---


## Complete Examples

The following are three complete examples from real-world application scenarios, demonstrating different uses of structured output.

### Example 1: Product Requirements Extraction

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
    
    // Define product requirements structure
    public static class ProductRequirements {
        public String productType;
        public String brand;
        public Integer minRam;
        public Double maxBudget;
        public List<String> features;
        
        public ProductRequirements() {}
    }
    
    public static void main(String[] args) {
        // Create Agent
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
        
        // Prepare query
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
            // Get structured output
            Msg response = agent.call(userMsg, ProductRequirements.class).block();
            ProductRequirements result = response.getStructuredData(ProductRequirements.class);
            
            // Print extracted data
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

**Output Example**:
```
Extracted structured data:
  Product Type: laptop
  Brand: Apple
  Min RAM: 16 GB
  Max Budget: $2000.0
  Features: [lightweight, travel-friendly]
```

### Example 2: Contact Information Extraction

```java
// Define contact information structure
public static class ContactInfo {
    public String name;
    public String email;
    public String phone;
    public String company;
    
    public ContactInfo() {}
}

// Usage example
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

**Output Example**:
```
Extracted contact information:
  Name: John Smith
  Email: john.smith@example.com
  Phone: +1-555-123-4567
  Company: TechCorp Inc.
```

### Example 3: Sentiment Analysis

```java
// Define sentiment analysis structure
public static class SentimentAnalysis {
    public String overallSentiment;  // "positive", "negative", or "neutral"
    public Double positiveScore;     // 0.0 to 1.0
    public Double negativeScore;     // 0.0 to 1.0
    public Double neutralScore;      // 0.0 to 1.0
    public List<String> keyTopics;
    public String summary;
    
    public SentimentAnalysis() {}
}

// Usage example
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

**Output Example**:
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

## Advanced Usage

### 1. Complex Nested Structures

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

### 2. Validation and Error Handling

```java
try {
    Msg response = agent.call(userMsg, ProductInfo.class).block();
    ProductInfo data = response.getStructuredData(ProductInfo.class);
    
    // Business validation
    if (data.price == null || data.price < 0) {
        System.err.println("Invalid price");
    }
    
} catch (IllegalArgumentException e) {
    System.err.println("Schema conversion failed: " + e.getMessage());
} catch (Exception e) {
    System.err.println("Processing failed: " + e.getMessage());
}
```

### 3. Conditional Structured Output

```java
// Dynamically decide whether structured output is needed
Class<?> schema = needsStructuredOutput ? ProductInfo.class : null;

Msg response = schema != null 
    ? agent.call(userMsg, schema).block()
    : agent.call(userMsg).block();
```

### 4. Multi-Step Structuring

```java
// Step 1: Extract entities
Msg step1 = agent.call(userMsg, EntityList.class).block();
EntityList entities = step1.getStructuredData(EntityList.class);

// Step 2: Analyze relationships
Msg step2 = agent.call(
    Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder()
            .text("Analyze the relationships between these entities: " + entities)
            .build())
        .build(),
    RelationshipGraph.class
).block();
```


## Advanced Features

### 1. Custom Validation

```java
Msg response = agent.call(userMsg, ProductInfo.class).block();
ProductInfo data = response.getStructuredData(ProductInfo.class);

// Custom business validation
if (data.price != null && data.price < 0) {
    throw new IllegalArgumentException("Price cannot be negative");
}

if (data.features == null || data.features.isEmpty()) {
    throw new IllegalArgumentException("Must have at least one feature");
}
```

### 2. Using Jackson Annotations

```java
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class CustomSchema {
    @JsonProperty("product_name")  // Custom JSON field name
    public String productName;
    
    @JsonIgnore  // Ignore this field
    public transient String internalCache;
}
```

### 3. Generic Support

```java
public class Response<T> {
    public boolean success;
    public String message;
    public T data;
}

// When using, specify concrete type
// Note: Generic erasure limitation, need to create concrete class
public class UserResponse extends Response<User> {}
```



---

## More Resources

- **Complete Example Code**: [StructuredOutputExample.java](../../../agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/StructuredOutputExample.java)
- **Agent Configuration**: [agent-config.md](./agent-config.md) - Learn about StructuredOutputReminder configuration
- **Model Documentation**: [model.md](./model.md) - Learn about model configuration
- **Tool Documentation**: [tool.md](./tool.md) - Learn about tool mechanism principles

