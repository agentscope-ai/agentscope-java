package io.agentscope.examples.mcp;

import io.agentscope.core.mcp.server.McpServer;
import io.agentscope.core.mcp.transport.StdioTransport;
import java.util.logging.Logger;

/**
 * CLI runner to start an MCP server with stdio transport.
 *
 * <p>This server exposes:
 * - calculator.compute: Basic arithmetic operations
 * - openai.chat: OpenAI Chat API integration (requires OPENAI_API_KEY env var)
 *
 * <p>Usage:
 * <pre>
 *   java -jar mcp-native-example.jar
 * </pre>
 *
 * <p>Or with OpenAI API key:
 * <pre>
 *   OPENAI_API_KEY="sk-..." java -jar mcp-native-example.jar
 * </pre>
 *
 * <p>The server communicates via stdin/stdout using JSON-RPC 2.0 protocol.
 */
public class McpServerRunner {

    private static final Logger logger = Logger.getLogger(McpServerRunner.class.getName());

    public static void main(String[] args) throws Exception {
        logger.info("Starting MCP Server with Stdio Transport...");

        // Create server with stdio transport
        StdioTransport transport = new StdioTransport();
        McpServer server = new McpServer(transport);

        // Register tools
        server.registerTool(new CalculatorTool());
        logger.info("Registered tool: calculator.compute");

        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            server.registerTool(new OpenAiChatTool(openaiApiKey));
            logger.info("Registered tool: openai.chat");
        } else {
            logger.warning("OPENAI_API_KEY not set; openai.chat tool will not be available");
        }

        // Start processing messages
        logger.info("Server ready for connections. Listening on stdin/stdout...");
        server.start();

        // Keep the server running
        Thread.currentThread().join();
    }
}
