package com.gazapps.inference.tooluse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

public class ToolUseInference implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(ToolUseInference.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final Llm llmService;
    private final boolean debugMode;
    private final int maxToolChainLength;
    private final boolean enableToolChaining;
    
    private List<FunctionDeclaration> availableMcpTools;
    private ToolChain toolChain;

    public ToolUseInference(Llm llmService, MCPService mcpService, MCPServers mcpServers, Map<String, Object> options) {
        this.llmService = Objects.requireNonNull(llmService);
        this.mcpService = Objects.requireNonNull(mcpService);
        this.mcpServers = Objects.requireNonNull(mcpServers);
        this.debugMode = (Boolean) options.getOrDefault("debug", false);
        this.maxToolChainLength = (Integer) options.getOrDefault("maxToolChainLength", 3);
        this.enableToolChaining = (Boolean) options.getOrDefault("enableToolChaining", false);
        
        this.toolChain = new ToolChain(mcpService, mcpServers, debugMode);
        initializeAvailableMcpTools();
    }

    @Override
    public String processQuery(String query) {
        try {
            if (debugMode) {
                logger.info("[TOOLUSE] Processing query: {}", query);
            }
            
            toolChain.clear();
            
            // Analyze query and determine if tools are needed
            String analysisResult = analyzeQuery(query);
            
            // Check if analysis indicates tool usage
            if (requiresToolExecution(analysisResult)) {
                return executeToolWorkflow(query, analysisResult);
            }
            
            // Direct response without tools
            return generateDirectResponse(query);
            
        } catch (Exception e) {
            logger.error("[TOOLUSE] Error processing query", e);
            return "Erro no processamento: " + e.getMessage();
        }
    }

    private String analyzeQuery(String query) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Analyze this user query and determine if tools are needed.\n\n");
        prompt.append("Query: ").append(query).append("\n\n");
        
        prompt.append("Available tools:\n");
        if (availableMcpTools != null) {
            for (FunctionDeclaration tool : availableMcpTools) {
                prompt.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            }
        }
        
        prompt.append("\nRespond with:\n");
        prompt.append("- 'DIRECT_RESPONSE' if you can answer without tools\n");
        prompt.append("- 'USE_TOOL:tool_name' if a single tool is needed\n");
        if (enableToolChaining) {
            prompt.append("- 'TOOL_CHAIN:tool1,tool2,tool3' if multiple tools are needed\n");
        }
        
        return llmService.generateResponse(prompt.toString(), null);
    }

    private boolean requiresToolExecution(String analysisResult) {
        return analysisResult.startsWith("USE_TOOL:") || 
               (enableToolChaining && analysisResult.startsWith("TOOL_CHAIN:"));
    }

    private String executeToolWorkflow(String query, String analysisResult) {
        try {
            if (analysisResult.startsWith("USE_TOOL:")) {
                return executeSingleTool(query, analysisResult);
            } else if (enableToolChaining && analysisResult.startsWith("TOOL_CHAIN:")) {
                return executeToolChain(query, analysisResult);
            }
            
            return generateDirectResponse(query);
            
        } catch (Exception e) {
            logger.error("[TOOLUSE] Error in tool workflow", e);
            return "Erro na execução de ferramentas: " + e.getMessage();
        }
    }

    private String executeSingleTool(String query, String analysisResult) {
        String toolName = analysisResult.substring("USE_TOOL:".length()).trim();
        
        if (debugMode) {
            logger.info("[TOOLUSE] Single tool execution: {}", toolName);
        }
        
        // Get tool parameters from LLM
        Map<String, Object> parameters = getToolParameters(query, toolName);
        
        // Execute tool
        ToolExecution execution = toolChain.execute(toolName, parameters);
        
        // Generate response
        return generateToolResponse(query, execution);
    }

    private String executeToolChain(String query, String analysisResult) {
        String toolsString = analysisResult.substring("TOOL_CHAIN:".length()).trim();
        String[] toolNames = toolsString.split(",");
        
        if (debugMode) {
            logger.info("[TOOLUSE] Tool chain execution: {}", java.util.Arrays.toString(toolNames));
        }
        
        List<ToolExecution> executions = new ArrayList<>();
        String lastResult = null;
        
        for (int i = 0; i < toolNames.length && i < maxToolChainLength; i++) {
            String toolName = toolNames[i].trim();
            
            // Get parameters for this tool (potentially using previous result)
            Map<String, Object> parameters = getToolParametersForChain(query, toolName, lastResult, i);
            
            // Execute tool
            ToolExecution execution = toolChain.execute(toolName, parameters);
            executions.add(execution);
            
            if (!execution.isSuccess()) {
                break; // Stop chain on failure
            }
            
            lastResult = execution.getResult();
        }
        
        // Generate final response from chain results
        return generateChainResponse(query, executions);
    }

    private Map<String, Object> getToolParameters(String query, String toolName) {
        try {
            String prompt = String.format(
                "Extract parameters for tool '%s' based on user query.\n\n" +
                "Query: %s\n\n" +
                "Respond with JSON format: {\"param1\":\"value1\", \"param2\":\"value2\"}\n" +
                "Use your knowledge for missing information (e.g., coordinates for cities).",
                toolName, query
            );
            
            String response = llmService.generateResponse(prompt, null);
            return objectMapper.readValue(response, Map.class);
            
        } catch (Exception e) {
            logger.warn("[TOOLUSE] Error extracting parameters for {}: {}", toolName, e.getMessage());
            return Map.of(); // Return empty map as fallback
        }
    }

    private Map<String, Object> getToolParametersForChain(String query, String toolName, String previousResult, int stepIndex) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append(String.format("Extract parameters for tool '%s' (step %d in chain).\n\n", toolName, stepIndex + 1));
            prompt.append("Original query: ").append(query).append("\n");
            
            if (previousResult != null) {
                prompt.append("Previous step result: ").append(previousResult).append("\n");
            }
            
            prompt.append("\nRespond with JSON format: {\"param1\":\"value1\", \"param2\":\"value2\"}\n");
            prompt.append("Use previous result data when relevant for this tool.");
            
            String response = llmService.generateResponse(prompt.toString(), null);
            return objectMapper.readValue(response, Map.class);
            
        } catch (Exception e) {
            logger.warn("[TOOLUSE] Error extracting chain parameters for {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }

    private String generateDirectResponse(String query) {
        String prompt = "Answer this query directly using your knowledge: " + query;
        return llmService.generateResponse(prompt, null);
    }

    private String generateToolResponse(String query, ToolExecution execution) {
        if (!execution.isSuccess()) {
            return "Erro ao executar ferramenta: " + execution.getError();
        }
        
        String prompt = String.format(
            "User asked: \"%s\"\n\n" +
            "Tool used: %s\n" +
            "Tool result: %s\n\n" +
            "Create a clear, helpful response in Portuguese based on the tool result.",
            query, execution.getToolName(), execution.getResult()
        );
        
        return llmService.generateResponse(prompt, null);
    }

    private String generateChainResponse(String query, List<ToolExecution> executions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("User asked: \"%s\"\n\n", query));
        prompt.append("Tool chain execution results:\n");
        
        for (int i = 0; i < executions.size(); i++) {
            ToolExecution exec = executions.get(i);
            prompt.append(String.format("Step %d - %s: ", i + 1, exec.getToolName()));
            if (exec.isSuccess()) {
                prompt.append(exec.getResult()).append("\n");
            } else {
                prompt.append("FAILED - ").append(exec.getError()).append("\n");
                break;
            }
        }
        
        prompt.append("\nCreate a comprehensive response in Portuguese summarizing the results.");
        
        return llmService.generateResponse(prompt.toString(), null);
    }

    private void initializeAvailableMcpTools() {
        if (mcpServers == null) return;
        
        try {
            availableMcpTools = new ArrayList<>();
            
            for (Map.Entry<String, McpSyncClient> entry : mcpServers.mcpClients.entrySet()) {
                String serverName = entry.getKey();
                McpSyncClient client = entry.getValue();
                ListToolsResult toolsResult = client.listTools();
                List<io.modelcontextprotocol.spec.McpSchema.Tool> serverTools = toolsResult.tools();
                for (io.modelcontextprotocol.spec.McpSchema.Tool mcpTool : serverTools) {
                    String toolName = mcpTool.name();
                    String namespacedToolName = serverName + "_" + toolName;
                    FunctionDeclaration geminiFunction = llmService.convertMcpToolToFunction(mcpTool, namespacedToolName);
                    availableMcpTools.add(geminiFunction);
                }
            }
            
            if (debugMode) {
                logger.info("[TOOLUSE] Initialized {} tools", availableMcpTools.size());
            }
            
        } catch (Exception e) {
            logger.error("Erro ao inicializar ferramentas MCP", e);
            availableMcpTools = new ArrayList<>();
        }
    }

    public ToolChain getToolChain() {
        return toolChain;
    }

    @Override
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("ToolUse Inference - Intelligent tool selection and execution strategy\n");
        prompt.append("Available tools: ").append(availableMcpTools != null ? availableMcpTools.size() : 0).append("\n");
        if (enableToolChaining) {
            prompt.append("Tool chaining: enabled (max length: ").append(maxToolChainLength).append(")\n");
        }
        return prompt.toString();
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
