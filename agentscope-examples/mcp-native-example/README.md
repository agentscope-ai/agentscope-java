# MCP Native Example

This example demonstrates how to use the native MCP (Model Context Protocol) server implementation with custom tools.

## Overview

The example includes:
- **CalculatorTool**: A simple arithmetic calculator supporting add, subtract, multiply, divide
- **OpenAiChatTool**: Integration with OpenAI Chat API (requires `OPENAI_API_KEY`)
- **McpServerRunner**: CLI to start the MCP server with stdio transport

## Building

```bash
mvn clean package -pl agentscope-examples/mcp-native-example
```

This creates a fat JAR: `target/mcp-native-example.jar`

## Running

### Basic Usage (Calculator Only)

```bash
java -jar target/mcp-native-example.jar
```

### With OpenAI Integration

```bash
OPENAI_API_KEY="sk-your-key-here" java -jar target/mcp-native-example.jar
```

## Testing with MCP Client

The server communicates via stdin/stdout using JSON-RPC 2.0.

### Example: Test Calculator Tool

Send a JSON-RPC request to stdin:

```json
{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "calculator.compute", "arguments": {"operation": "add", "a": 5, "b": 3}}}
```

Expected response:

```json
{"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "{\"operation\":\"add\",\"a\":5.0,\"b\":3.0,\"result\":8.0}"}]}}
```

### Example: List Available Tools

```json
{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
```

### Example: Initialize Handshake

```json
{"jsonrpc": "2.0", "id": 3, "method": "initialize"}
```

## Architecture

The example uses:
- `StdioTransport`: JSON-RPC communication over stdin/stdout
- `McpServer`: Facade for tool registration and message routing
- `ToolManager`: Registry for server-side tools
- Handler classes: `InitializeHandler`, `ListToolsHandler`, `CallToolHandler`

## Environment Variables

- `OPENAI_API_KEY`: OpenAI API key (optional; enables openai.chat tool)

## Files

- `McpServerRunner.java`: Main entry point
- `CalculatorTool.java`: Calculator implementation
- `OpenAiChatTool.java`: OpenAI Chat API integration
- `CalculatorToolTest.java`: Unit tests
