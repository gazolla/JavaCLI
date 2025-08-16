package com.gazapps.inference.tooluse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;

public class ToolChain {
    
    private static final Logger logger = LoggerFactory.getLogger(ToolChain.class);
    
    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final List<ToolExecution> executions;

    
    public ToolChain(MCPService mcpService, MCPServers mcpServers) {
        this.mcpService = mcpService;
        this.mcpServers = mcpServers;
        this.executions = new ArrayList<>();
    }
    
    public ToolExecution execute(String toolName, Map<String, Object> arguments) {
        long startTime = System.currentTimeMillis();
        
        try {
            logger.info("[TOOLCHAIN] Executing: {}({})", toolName, arguments);

            
            String serverName = mcpServers.getServerForTool(toolName);
            McpSyncClient client = mcpServers.getClient(serverName);
            String originalToolName = toolName.substring(serverName.length() + 1);
            String result = mcpService.executeToolByName(client, originalToolName, arguments);
            
            long executionTime = System.currentTimeMillis() - startTime;
            ToolExecution execution = ToolExecution.success(toolName, arguments, result, executionTime);
            executions.add(execution);
            logger.info("[TOOLCHAIN] Success: {} ({}ms)", toolName, executionTime);
            
            return execution;
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String error = "Tool execution failed: " + e.getMessage();
            ToolExecution execution = ToolExecution.failure(toolName, arguments, error, executionTime);
            executions.add(execution);
            logger.error("[TOOLCHAIN] Failed: {} - {} ({}ms)", toolName, error, executionTime);

            return execution;
        }
    }
    
    public List<ToolExecution> executeSequential(List<String> toolNames, List<Map<String, Object>> argumentsList) {
        List<ToolExecution> results = new ArrayList<>();
        
        for (int i = 0; i < toolNames.size() && i < argumentsList.size(); i++) {
            ToolExecution execution = execute(toolNames.get(i), argumentsList.get(i));
            results.add(execution);
            
            // Stop on failure if configured to do so
            if (!execution.isSuccess()) {
                logger.warn("[TOOLCHAIN] Stopping chain due to failure: {}", execution.getError());
                break;
            }
        }
        
        return results;
    }
    
    public List<ToolExecution> getExecutions() {
        return new ArrayList<>(executions);
    }
    
    public boolean hasFailures() {
        return executions.stream().anyMatch(exec -> !exec.isSuccess());
    }
    
    public long getTotalExecutionTime() {
        return executions.stream().mapToLong(ToolExecution::getExecutionTimeMs).sum();
    }
    
    public void clear() {
        executions.clear();
    }
    
    @Override
    public String toString() {
        return String.format("ToolChain{executions=%d, failures=%d, totalTime=%dms}", 
                           executions.size(), 
                           (int) executions.stream().filter(e -> !e.isSuccess()).count(),
                           getTotalExecutionTime());
    }
}
