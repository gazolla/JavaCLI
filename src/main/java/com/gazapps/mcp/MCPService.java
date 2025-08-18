package com.gazapps.mcp;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.exceptions.MCPException;
import com.gazapps.exceptions.ConfigException;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class MCPService {

    private static final Logger logger = LoggerFactory.getLogger(MCPService.class);
    
    public int requestTimeoutSeconds = 5;

    public static class ServerConfig {

        public static enum ServerPriority {
            HIGH(3),
            MEDIUM(2),
            LOW(1),
            UNCLASSIFIED(0);

            private final int value;
            ServerPriority(int value) {  this.value = value; }
            public int getValue() { return this.value; }
            public static ServerPriority fromValue(int value) {
                return switch (value) {
                    case 3 -> HIGH;
                    case 2 -> MEDIUM;
                    case 1 -> LOW;
                    case 0 -> UNCLASSIFIED;
                    default -> throw new IllegalArgumentException("Valor de prioridade inválido: " + value);
                };
            }
        }

        public String name;
        public String url;
        public String type;
        public Map<String, String> environment = new HashMap<>();
        public boolean enabled = true;
        

        public ServerPriority priority = ServerPriority.UNCLASSIFIED;
        
        public String description = "";
        public String path = "";

        public ServerConfig() {
        }

        public ServerConfig(String name, String url, String type) {
            this.name = name;
            this.url = url;
            this.type = type;
        }

        public ServerConfig(String name, String url, String type, String path) {
            this.name = name;
            this.url = url;
            this.type = type;
            this.path = path;
        }

        @Override
        public String toString() {
            String status = enabled ? "✅" : "❌";
            return String.format("%s %s (%s) - %s [Prioridade: %s]", status, name, type, description, priority.name());
        }
    }

    
    public enum ServerStatus {
        CONNECTED, ERROR, DISCONNECTED
    }

    public McpSyncClient createMcpClient(ServerConfig serverConfig) {
        try {
            return switch (serverConfig.type.toLowerCase()) {
                case "http" -> {
                    HttpClientSseClientTransport httpTransport = 
                        HttpClientSseClientTransport.builder(serverConfig.url)
                            .build();
                    yield McpClient.sync(httpTransport)
                        .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .build();
                }
                
                case "stdio" -> {
                    String[] command;
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        command = new String[]{"cmd.exe", "/c", serverConfig.url};
                        System.out.println(Arrays.toString(command));
                    } else {
                        command = serverConfig.url.split(" ");
                    }
                    
                    ServerParameters serverParams = ServerParameters.builder(command[0])
                        .args(Arrays.copyOfRange(command, 1, command.length))
                        .build();
                        
                    StdioClientTransport stdioTransport = new StdioClientTransport(serverParams);
                    yield McpClient.sync(stdioTransport)
                        .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .build();
                }
                
                default -> throw new IllegalArgumentException("Transport not supported: " + serverConfig.type);
            };
        } catch (Exception e) {
            System.err.println(e);
            throw new RuntimeException("Error creating client MCP: " + e.getMessage(), e);
        }
    }
    
    public McpSyncClient connectToServer(ServerConfig serverConfig) throws Exception {
        try {
        	System.out.println("Connecting to MCP server '" + serverConfig.name + "'");
            McpSyncClient client = createMcpClient(serverConfig);
            client.initialize();
            return client;
            
        } catch (Exception e) {
        	System.out.println("Failed to connect to MCP server '" + serverConfig.name + "': " + e.getMessage());
            throw new ConfigException("Failed to connect to MCP server '" + serverConfig.name + "': " + e.getMessage(), e);
        }
    }
    
    public String executeToolByName(McpSyncClient client, String toolName, Map<String, Object> arguments) throws Exception {
        try {
            CallToolRequest request = new CallToolRequest(toolName, arguments);
            CallToolResult result = client.callTool(request);

            Boolean isError = result.isError();
            if (isError != null && isError) {
                throw new MCPException("Tool returned error: " + result.toString(), "unknown", toolName);
            }

            List<Content> contentList = result.content();
            return extractContentAsString(contentList);
            
        } catch (Exception e) {
            if (e instanceof MCPException) {
                throw e; // Re-throw specific exception
            }
            
            // Convert other exceptions to MCPException
            String serverName = "unknown"; // You can pass this as parameter if needed
            throw new MCPException("Error executing tool: " + e.getMessage(), serverName, toolName, e);
        }
    }
    
    public List<Tool> discoverServerTools(McpSyncClient client) throws Exception {
        ListToolsResult result = client.listTools();
        return result.tools();
    }
    
    public List<Resource> discoverServerResources(McpSyncClient client) throws Exception {
    	ListResourcesResult  result = client.listResources();
        return result.resources();
    }
    
    public List<Prompt> discoverServerPrompts(McpSyncClient client) throws Exception {
    	ListPromptsResult  result = client.listPrompts();
        return result.prompts();
    }

    private String extractContentAsString(List<Content> contentList) {
        if (contentList == null || contentList.isEmpty()) {
            return "Sem mensagem de retorno";
        }

        for (Content content : contentList) {
            if (content instanceof TextContent textContent) {
                if (textContent.text() != null && !textContent.text().trim().isEmpty()) {
                    return textContent.text();
                }
            }
        }

        return "Nenhuma mensagem encontrada";
    }
}
