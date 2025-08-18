package com.gazapps.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Provides a searchable catalog and execution interface for MCP tools.
 * This class acts as a higher-level facade, abstracting away the complexities
 * of server connections and direct tool execution, providing a clean API
 * for discovering and using available tools.
 */
public class MCPInfo {

    private final MCPServers mcpServers;
    private final MCPService mcpService;

    /**
     * Constructs an instance of MCPInfo with its required dependencies.
     *
     * @param mcpServers The instance of the MCP servers manager.
     * @param mcpService The instance of the MCP service for tool execution.
     */
    public MCPInfo(MCPServers mcpServers, MCPService mcpService) {
        this.mcpServers = mcpServers;
        this.mcpService = mcpService;
    }

    /**
     * Returns a list of all loaded MCP servers.
     *
     * @return An unmodifiable list of ServerConfig objects.
     */
    public List<MCPService.ServerConfig> listServers() {
        return Collections.unmodifiableList(mcpServers.mcpServers);
    }

    /**
     * Returns a list of all available tools from all connected servers.
     *
     * @return A list of Tool objects.
     */
    public List<Tool> listAllTools() {
        return new ArrayList<>(mcpServers.availableMcpTools.values());
    }

    /**
     * Returns a list of tools provided by a specific server.
     *
     * @param serverName The name of the server.
     * @return A list of tools. Returns an empty list if the server is not found.
     */
    public List<Tool> listToolsByServer(String serverName) {
        return mcpServers.availableMcpTools.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(serverName + "_"))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Searches for tools based on a keyword in their name or description.
     *
     * @param keyword The keyword to search for.
     * @return A list of tools that match the keyword.
     */
    public List<Tool> listToolsByKeyWord(String keyword) {
        String lowerCaseKeyword = keyword.toLowerCase();
        return mcpServers.availableMcpTools.values().stream()
                .filter(tool -> tool.name().toLowerCase().contains(lowerCaseKeyword)
                        || tool.description().toLowerCase().contains(lowerCaseKeyword))
                .collect(Collectors.toList());
    }

    /**
     * Returns the "signature" of a tool, including its name, description, and input schema.
     *
     * @param namespacedToolName The full namespaced tool name (e.g., "server_name_tool_name").
     * @return A Tool object with the tool's details, or null if not found.
     */
    public Tool showToolSignature(String namespacedToolName) {
        return mcpServers.availableMcpTools.get(namespacedToolName);
    }
    
    /**
     * Checks if a specific tool is available.
     *
     * @param namespacedToolName The full namespaced tool name.
     * @return true if the tool is available, false otherwise.
     */
    public boolean isToolAvailable(String namespacedToolName) {
        return mcpServers.availableMcpTools.containsKey(namespacedToolName);
    }

    /**
     * Executes a tool with the given parameters.
     *
     * @param namespacedToolName The full namespaced tool name.
     * @param parameters The tool's arguments.
     * @return The result of the tool execution as a string.
     * @throws Exception If the tool cannot be executed.
     */
    public String executeTool(String namespacedToolName, Map<String, Object> parameters) throws Exception {
        if (!isToolAvailable(namespacedToolName)) {
            throw new IllegalArgumentException("Tool not available: " + namespacedToolName);
        }
        
        String serverName = mcpServers.getServerForTool(namespacedToolName);
        if (serverName == null) {
            throw new IllegalStateException("Server for tool '" + namespacedToolName + "' not found.");
        }
        
        McpSyncClient client = mcpServers.getClient(serverName);
        if (client == null) {
            throw new IllegalStateException("MCP client for server '" + serverName + "' is not connected.");
        }
        
        String toolName = namespacedToolName.substring(serverName.length() + 1);
        
        return mcpService.executeToolByName(client, toolName, parameters);
    }
    
    /**
     * Converts a simple tool name to its namespaced equivalent.
     * Ex: "get_current_time" -> "time_get_current_time"
     * 
     * @param simpleName Simple tool name
     * @return Namespaced tool name or null if not found
     */
    public String getNamespacedToolName(String simpleName) {
        return mcpServers.getNamespacedToolName(simpleName);
    }
    
    /**
     * Returns the formatted signature of a tool as a readable string.
     * This method correctly parses the JSON schema to display parameter details.
     *
     * @param namespacedToolName The full namespaced tool name.
     * @return A string with the tool's details.
     */
    public String formatToolSignature(String namespacedToolName) {
        Tool tool = showToolSignature(namespacedToolName);
        if (tool == null) {
            return "Tool not found.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(tool.name()).append("\n");
        sb.append("Description: ").append(tool.description()).append("\n");
        sb.append("Parameters:\n");
        
        if (tool.inputSchema() == null || tool.inputSchema().properties() == null || tool.inputSchema().properties().isEmpty()) {
            sb.append("  (No parameters needed)\n");
        } else {
            Map<String, Object> properties = tool.inputSchema().properties();
            List<String> required = tool.inputSchema().required();

            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String paramName = entry.getKey();
                Map<String, Object> paramDetails = (Map<String, Object>) entry.getValue();

                String paramType = (String) paramDetails.get("type");
                String paramDescription = (String) paramDetails.get("description");

                sb.append("  - ").append(paramName).append(" (").append(paramType).append("): ")
                  .append(paramDescription);
                
                if (required != null && required.contains(paramName)) {
                    sb.append(" (Required)");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}