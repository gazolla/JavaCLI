package com.gazapps.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Unified tool management: validation, caching, and execution coordination
 */
public class ToolManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ToolManager.class);
    
    private final MCPInfo mcpInfo;
    private final MCPServers mcpServers;
    private final Map<String, CachedToolInfo> toolCache = new ConcurrentHashMap<>();
    private final long cacheExpirationMs;
    private final int maxRetries;
    
    public ToolManager(MCPInfo mcpInfo, MCPServers mcpServers) {
        this.mcpInfo = mcpInfo;
        this.mcpServers = mcpServers;
        this.cacheExpirationMs = 300000; // 5 minutes
        this.maxRetries = 3;
    }
    
    /**
     * Validates tool availability and parameters
     */
    public boolean isToolReady(String toolName) {
        String namespacedName = resolveToolName(toolName);
        if (namespacedName == null) return false;
        
        CachedToolInfo cached = getCachedInfo(namespacedName);
        if (cached != null && !cached.isExpired()) {
            return cached.isAvailable();
        }
        
        boolean available = mcpInfo.isToolAvailable(namespacedName);
        cacheToolInfo(namespacedName, available);
        return available;
    }
    
    /**
     * Gets available tools with caching
     */
    public List<Tool> getAvailableTools() {
        List<Tool> allTools = mcpInfo.listAllTools();
        
        // Filter only available tools
        return allTools.stream()
            .filter(tool -> {
                try {
                    return isToolReady(tool.name());
                } catch (Exception e) {
                    logger.warn("Error checking tool availability for {}: {}", tool.name(), e.getMessage());
                    return false;
                }
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Validates and executes tool with fallback
     */
    public ToolOperationResult validateAndExecute(String toolName, Map<String, Object> parameters) {
        if (!isToolReady(toolName)) {
            return ToolOperationResult.failure("Tool not available: " + toolName, 
                getSuggestedAlternatives(toolName));
        }
        
        String namespacedName = resolveToolName(toolName);
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String result = mcpInfo.executeTool(namespacedName, parameters);
                return ToolOperationResult.success(result);
                
            } catch (Exception e) {
                logger.warn("Tool execution attempt {}/{} failed for {}: {}", 
                    attempt, maxRetries, toolName, e.getMessage());
                
                if (attempt == maxRetries) {
                    invalidateToolCache(namespacedName);
                    return ToolOperationResult.failure(
                        "Tool execution failed after " + maxRetries + " attempts: " + e.getMessage(),
                        getSuggestedAlternatives(toolName));
                }
                
                try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { break; }
            }
        }
        
        return ToolOperationResult.failure("Tool execution failed", getSuggestedAlternatives(toolName));
    }
    
    /**
     * Gets formatted tool information for prompts
     */
    public String getFormattedToolInfo() {
        StringBuilder info = new StringBuilder("AVAILABLE TOOLS:\n");
        
        List<Tool> availableTools = getAvailableTools();
        if (availableTools.isEmpty()) {
            return "AVAILABLE TOOLS:\n(No tools available)\n";
        }
        
        for (Tool tool : availableTools) {
            String signature = mcpInfo.formatToolSignature(tool.name());
            if (signature != null && !signature.trim().equals("Tool not found.")) {
                info.append(signature).append("\n");
            } else {
                // Fallback formatting
                info.append("Name: ").append(tool.name()).append("\n");
                info.append("Description: ").append(tool.description()).append("\n\n");
            }
        }
        
        return info.toString();
    }
    
    /**
     * Suggests alternative tools for failed operations
     */
    public List<String> getSuggestedAlternatives(String failedToolName) {
        return mcpInfo.listToolsByKeyWord(extractKeyword(failedToolName)).stream()
            .map(Tool::name)
            .filter(name -> !name.equals(failedToolName))
            .limit(3)
            .collect(Collectors.toList());
    }
    
    /**
     * Invalidates cache for specific tool
     */
    public void invalidateToolCache(String toolName) {
        toolCache.remove(toolName);
    }
    
    /**
     * Clears entire cache
     */
    public void clearCache() {
        toolCache.clear();
    }
    
    // Private helper methods
    
    private String resolveToolName(String simpleName) {
        // Log para debug
        logger.debug("Resolving tool name: {}", simpleName);
        
        // Primeiro, verificar se já é um nome com namespace
        if (mcpInfo.isToolAvailable(simpleName)) {
            logger.debug("Tool {} found as-is", simpleName);
            return simpleName;
        }
        
        // Tentar obter nome com namespace via MCPServers
        String namespaced = mcpServers.getNamespacedToolName(simpleName);
        if (namespaced != null) {
            logger.debug("Tool {} resolved to {}", simpleName, namespaced);
            return namespaced;
        }
        
        // Tentar buscar em todas as ferramentas disponíveis
        for (Tool tool : mcpInfo.listAllTools()) {
            String toolName = tool.name();
            if (toolName.equals(simpleName) || toolName.endsWith("_" + simpleName)) {
                logger.debug("Tool {} found as {}", simpleName, toolName);
                return toolName;
            }
        }
        
        logger.warn("Could not resolve tool name: {}", simpleName);
        return simpleName; // Fallback
    }
    
    private CachedToolInfo getCachedInfo(String toolName) {
        CachedToolInfo cached = toolCache.get(toolName);
        return (cached != null && !cached.isExpired()) ? cached : null;
    }
    
    private void cacheToolInfo(String toolName, boolean available) {
        toolCache.put(toolName, new CachedToolInfo(available, System.currentTimeMillis()));
    }
    
    private String extractKeyword(String toolName) {
        // Extract main keyword from tool name (e.g., "weather" from "weather-nws_get-forecast")
        String[] parts = toolName.split("[-_]");
        return parts.length > 0 ? parts[0] : toolName;
    }
    
    // Inner classes
    
    public static class ToolOperationResult {
        private final boolean success;
        private final String result;
        private final String errorMessage;
        private final List<String> suggestions;
        
        private ToolOperationResult(boolean success, String result, String errorMessage, List<String> suggestions) {
            this.success = success;
            this.result = result;
            this.errorMessage = errorMessage;
            this.suggestions = suggestions;
        }
        
        public static ToolOperationResult success(String result) {
            return new ToolOperationResult(true, result, null, null);
        }
        
        public static ToolOperationResult failure(String errorMessage, List<String> suggestions) {
            return new ToolOperationResult(false, null, errorMessage, suggestions);
        }
        
        public boolean isSuccess() { return success; }
        public String getResult() { return result; }
        public String getErrorMessage() { return errorMessage; }
        public List<String> getSuggestions() { return suggestions; }
    }
    
    private static class CachedToolInfo {
        private final boolean available;
        private final long cacheTime;
        
        CachedToolInfo(boolean available, long cacheTime) {
            this.available = available;
            this.cacheTime = cacheTime;
        }
        
        boolean isAvailable() { return available; }
        
        boolean isExpired() {
            return System.currentTimeMillis() - cacheTime > 300000; // 5 minutes
        }
    }

	/**
	 * Returns the MCPInfo instance used by this ToolManager.
	 * This provides access to MCP-related information and operations.
	 * 
	 * @return the MCPInfo instance
	 */
	public MCPInfo getMcpInfo() {
		return mcpInfo;
	}
}
