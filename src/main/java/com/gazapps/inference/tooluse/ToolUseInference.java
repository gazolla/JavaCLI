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
    
    // LLM-driven parameter extraction constants
    private static final int MAX_RETRIES = 2;
    private static final String DEFAULT_TIMEZONE = "America/Sao_Paulo";

    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final Llm llmService;
    private final boolean debugMode;
    private final int maxToolChainLength;
    private final boolean enableToolChaining;
    
    private List<FunctionDeclaration> availableMcpTools;
    private ToolChain toolChain;
    private final Map<String, JsonNode> toolSchemaCache = new java.util.HashMap<>();

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
            String analysisResult = analyzeQuery(query);
            
            if (requiresToolExecution(analysisResult)) {
                return executeToolWorkflow(query, analysisResult);
            }
            
            return generateDirectResponse(query);
            
        } catch (Exception e) {
            logger.error("[TOOLUSE] Error processing query", e);
            return "Erro no processamento: " + e.getMessage();
        }
    }

    private String analyzeQuery(String query) {
        String queryLower = query.toLowerCase();
        
        if (availableMcpTools != null) {
            for (FunctionDeclaration tool : availableMcpTools) {
                String description = tool.description.toLowerCase();
                if (isQueryMatchingTool(queryLower, description, tool.name)) {
                    return "USE_TOOL:" + tool.name;
                }
            }
        }
        
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
    
    private boolean isQueryMatchingTool(String queryLower, String description, String toolName) {
        // Time operations - PRIORITY FIX
        if (queryLower.contains("hora") || queryLower.contains("time") || queryLower.contains("horário") ||
            queryLower.contains("dia") || queryLower.contains("date") || queryLower.contains("quando")) {
            return description.contains("time") || description.contains("timezone");
        }
        
        // RSS/Feed/News operations - PRIORITY FIX
        if (queryLower.contains("feed") || queryLower.contains("rss") || queryLower.contains("notícias") ||
            queryLower.contains("manchetes") || queryLower.contains("news") || 
            (queryLower.contains("mostre") && (queryLower.contains(".com") || queryLower.contains("site")))) {
            return description.contains("feed") || description.contains("rss");
        }
        
        // Weather operations
        if (queryLower.contains("clima") || queryLower.contains("weather") || 
            queryLower.contains("tempo") || queryLower.contains("temperatura")) {
            if (toolName.toLowerCase().contains("forecast")) {
                return description.contains("forecast") || description.contains("weather");
            }
            if (queryLower.contains("alert") || queryLower.contains("aviso")) {
                return toolName.toLowerCase().contains("alert");
            }
            return false;
        }
        
        // File operations
        if (queryLower.contains("liste") || queryLower.contains("listar") || queryLower.contains("list")) {
            return toolName.toLowerCase().contains("list_directory") && description.contains("list") && description.contains("directory");
        }
        
        if (queryLower.contains("crie") || queryLower.contains("create") || queryLower.contains("arquivo")) {
            return toolName.toLowerCase().contains("write") && 
                   (description.contains("create") || description.contains("write")) && 
                   description.contains("file");
        }
        
        if (queryLower.contains("leia") || queryLower.contains("read") || queryLower.contains("abrir")) {
            return toolName.toLowerCase().contains("read_file") && 
                   description.contains("read") && description.contains("file");
        }
        
        // Knowledge graph operations
        if (queryLower.contains("entidade") || queryLower.contains("entity") || queryLower.contains("conhecimento")) {
            return description.contains("entity") || description.contains("knowledge") || description.contains("graph");
        }
        
        return false;
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
            logger.info("[TOOLUSE] Original query: {}", query);
        }
        
        JsonNode toolSchema = getToolSchema(toolName);
        ToolExecution execution = executeWithLLMRetry(toolName, query, toolSchema);
        
        if (debugMode) {
            logger.info("[TOOLUSE] Execution result: success={}, result={}", 
                       execution.isSuccess(), execution.getResult());
        }
        
        return generateToolResponse(query, execution);
    }

    /**
     * LLM-driven parameter extraction with retry mechanism - KEY METHOD
     */
    private ToolExecution executeWithLLMRetry(String toolName, String query, JsonNode toolSchema) {
        ToolExecution execution = null;
        Map<String, Object> parameters = null;
        
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt == 0) {
                    parameters = extractParametersWithLLM(toolName, query, toolSchema);
                } else {
                    parameters = correctParametersWithLLM(toolName, query, parameters, execution.getError(), toolSchema);
                }
                
                if (debugMode) {
                    logger.info("[TOOLUSE] Attempt {}: Extracted parameters: {}", attempt + 1, parameters);
                }
                
                execution = toolChain.execute(toolName, parameters);
                
                if (execution.isSuccess()) {
                    return execution;
                }
                
                if (!isParameterValidationError(execution.getError())) {
                    break;
                }
                
                if (debugMode) {
                    logger.warn("[TOOLUSE] Attempt {} failed with parameter error: {}", 
                               attempt + 1, execution.getError());
                }
                
            } catch (Exception e) {
                logger.warn("[TOOLUSE] Error in attempt {}: {}", attempt + 1, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    return ToolExecution.failure(toolName, parameters, 
                                               "Parameter extraction failed after retries: " + e.getMessage(), 0);
                }
            }
        }
        
        return execution;
    }

    /**
     * Extract parameters using LLM - REPLACES OLD HARDCODED METHOD
     */
    private Map<String, Object> extractParametersWithLLM(String toolName, String query, JsonNode toolSchema) {
        String prompt = buildParameterExtractionPrompt(toolName, query, toolSchema);
        String response = llmService.generateResponse(prompt, null);
        return parseParametersFromLLMResponse(response);
    }

    /**
     * Correct parameters using LLM based on error feedback
     */
    private Map<String, Object> correctParametersWithLLM(String toolName, String query, 
                                                         Map<String, Object> failedParams, 
                                                         String errorMessage, JsonNode toolSchema) {
        String prompt = buildParameterCorrectionPrompt(toolName, query, failedParams, errorMessage, toolSchema);
        String response = llmService.generateResponse(prompt, null);
        return parseParametersFromLLMResponse(response);
    }

    /**
     * Build comprehensive parameter extraction prompt
     */
    private String buildParameterExtractionPrompt(String toolName, String query, JsonNode toolSchema) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are extracting parameters for MCP tool execution.\n\n");
        prompt.append("Tool: ").append(toolName).append("\n");
        prompt.append("User Query: \"").append(query).append("\"\n\n");
        
        if (toolSchema != null) {
            prompt.append("Tool Schema:\n");
            prompt.append(toolSchema.toPrettyString()).append("\n\n");
        }
        
        prompt.append("CRITICAL EXTRACTION RULES:\n");
        prompt.append("1. Extract ONLY parameters defined in the tool schema\n");
        prompt.append("2. Use your knowledge to provide accurate values:\n\n");
        
        // TIME/TIMEZONE - HIGHEST PRIORITY FIX
        if (toolName.contains("time") || toolName.contains("datetime")) {
            prompt.append("TIME/TIMEZONE EXTRACTION (REQUIRED):\n");
            prompt.append("- ALWAYS include 'timezone' parameter\n");
            prompt.append("- San Francisco/CA → \"timezone\": \"America/Los_Angeles\"\n");
            prompt.append("- New York/Miami/FL → \"timezone\": \"America/New_York\"\n");
            prompt.append("- Brazil/Default → \"timezone\": \"").append(DEFAULT_TIMEZONE).append("\"\n");
            prompt.append("- Tokyo/Japan → \"timezone\": \"Asia/Tokyo\"\n");
            prompt.append("- London/UK → \"timezone\": \"Europe/London\"\n\n");
        }
        
        // RSS/FEED - SECOND PRIORITY FIX
        if (toolName.contains("feed") || toolName.contains("rss")) {
            prompt.append("RSS/FEED EXTRACTION (REQUIRED):\n");
            prompt.append("- ALWAYS include 'url' parameter\n");
            prompt.append("- Add https:// prefix: metropoles.com → \"url\": \"https://metropoles.com\"\n");
            prompt.append("- Extract domain: g1.com → \"url\": \"https://g1.globo.com\"\n");
            prompt.append("- Use base website URL for feed detection\n\n");
        }
        
        // Weather coordinates
        if (toolName.contains("forecast") || toolName.contains("weather")) {
            prompt.append("WEATHER COORDINATES:\n");
            prompt.append("- Denver,CO → {\"latitude\": 39.7392, \"longitude\": -104.9903}\n");
            prompt.append("- Paris → {\"latitude\": 48.8566, \"longitude\": 2.3522}\n");
            prompt.append("- Tokyo → {\"latitude\": 35.6762, \"longitude\": 139.6503}\n\n");
        }
        
        // File operations
        if (toolName.contains("write") || toolName.contains("create") || toolName.contains("file")) {
            prompt.append("FILE OPERATIONS:\n");
            // Get current workspace path dynamically
            String workspacePath = com.gazapps.config.EnvironmentSetup.getExpandedWorkspacePath();
            if (workspacePath != null) {
                prompt.append("- REQUIRED: Use FULL workspace path: ").append(workspacePath).append("\n");
                prompt.append("- For file 'example.txt' use: ").append(workspacePath).append("\\example.txt\n");
                prompt.append("- NEVER use relative paths - always include full workspace path\n");
            } else {
                prompt.append("- Use current directory for file operations\n");
            }
            prompt.append("- Generate relevant content based on the request\n\n");
        }
        
        prompt.append("Return ONLY valid JSON with the required parameters. No explanation.\n\n");
        prompt.append("JSON:\n");
        
        return prompt.toString();
    }

    /**
     * Build parameter correction prompt based on error feedback
     */
    private String buildParameterCorrectionPrompt(String toolName, String query, 
                                                  Map<String, Object> failedParams, 
                                                  String errorMessage, JsonNode toolSchema) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("TOOL EXECUTION FAILED. Fix the parameters.\n\n");
        prompt.append("Tool: ").append(toolName).append("\n");
        prompt.append("Query: \"").append(query).append("\"\n");
        prompt.append("Failed Parameters: ").append(failedParams).append("\n");
        prompt.append("Error: ").append(errorMessage).append("\n\n");
        
        if (toolSchema != null) {
            prompt.append("Tool Schema:\n");
            prompt.append(toolSchema.toPrettyString()).append("\n\n");
        }
        
        prompt.append("SPECIFIC FIXES NEEDED:\n");
        
        if (errorMessage.contains("timezone") && errorMessage.contains("required")) {
            prompt.append("TIMEZONE MISSING - Add timezone parameter:\n");
            prompt.append("- San Francisco/CA → \"timezone\": \"America/Los_Angeles\"\n");
            prompt.append("- Brazil/Default → \"timezone\": \"").append(DEFAULT_TIMEZONE).append("\"\n\n");
        }
        
        if (errorMessage.contains("url") && errorMessage.contains("required")) {
            prompt.append("URL MISSING - Add url parameter with https://:\n");
            prompt.append("- Extract domain and add https:// prefix\n\n");
        }
        
        prompt.append("Return corrected JSON parameters:\n");
        
        return prompt.toString();
    }

    /**
     * Parse LLM response to extract parameters
     */
    private Map<String, Object> parseParametersFromLLMResponse(String response) {
        try {
            String cleanResponse = response.trim();
            
            if (cleanResponse.contains("```")) {
                cleanResponse = cleanResponse.replaceAll("```json|```", "").trim();
            }
            
            int start = cleanResponse.indexOf('{');
            int end = cleanResponse.lastIndexOf('}');
            
            if (start >= 0 && end > start) {
                cleanResponse = cleanResponse.substring(start, end + 1);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(cleanResponse, Map.class);
            return result;
            
        } catch (Exception e) {
            logger.warn("[TOOLUSE] Error parsing LLM response: {}", e.getMessage());
            return new java.util.HashMap<>();
        }
    }

    /**
     * Get tool schema from available tools
     */
    private JsonNode getToolSchema(String toolName) {
        if (toolSchemaCache.containsKey(toolName)) {
            return toolSchemaCache.get(toolName);
        }
        
        try {
            if (availableMcpTools != null) {
                for (FunctionDeclaration tool : availableMcpTools) {
                    if (tool.name.equals(toolName)) {
                        JsonNode schema = objectMapper.valueToTree(tool.parameters);
                        toolSchemaCache.put(toolName, schema);
                        return schema;
                    }
                }
            }
            
            String serverName = mcpServers.getServerForTool(toolName);
            if (serverName != null) {
                McpSyncClient client = mcpServers.getClient(serverName);
                String originalToolName = toolName.substring(serverName.length() + 1);
                
                ListToolsResult toolsResult = client.listTools();
                for (io.modelcontextprotocol.spec.McpSchema.Tool mcpTool : toolsResult.tools()) {
                    if (mcpTool.name().equals(originalToolName)) {
                        JsonNode schema = objectMapper.valueToTree(mcpTool.inputSchema());
                        toolSchemaCache.put(toolName, schema);
                        return schema;
                    }
                }
            }
            
        } catch (Exception e) {
            logger.warn("[TOOLUSE] Error getting schema for tool {}: {}", toolName, e.getMessage());
        }
        
        return null;
    }

    /**
     * Check if error is related to parameter validation
     */
    private boolean isParameterValidationError(String errorMessage) {
        if (errorMessage == null) return false;
        
        return errorMessage.toLowerCase().contains("validation error") ||
               errorMessage.toLowerCase().contains("required property") ||
               errorMessage.toLowerCase().contains("missing") ||
               errorMessage.toLowerCase().contains("invalid parameter");
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
            Map<String, Object> parameters = getToolParametersForChain(query, toolName, lastResult, i);
            ToolExecution execution = toolChain.execute(toolName, parameters);
            executions.add(execution);
            
            if (!execution.isSuccess()) {
                break;
            }
            
            lastResult = execution.getResult();
        }
        
        return generateChainResponse(query, executions);
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
            return parseParametersFromLLMResponse(response);
            
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
                logger.info("[TOOLUSE] Initialized {} tools with LLM-driven parameter extraction", availableMcpTools.size());
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
        prompt.append("ToolUse Inference - LLM-driven intelligent tool selection and execution\n");
        prompt.append("Available tools: ").append(availableMcpTools != null ? availableMcpTools.size() : 0).append("\n");
        prompt.append("Auto-retry with error correction: enabled (max retries: ").append(MAX_RETRIES).append(")\n");
        prompt.append("Default timezone: ").append(DEFAULT_TIMEZONE).append("\n");
        if (enableToolChaining) {
            prompt.append("Tool chaining: enabled (max length: ").append(maxToolChainLength).append(")\n");
        }
        return prompt.toString();
    }

    @Override
    public void close() {
        toolSchemaCache.clear();
        if (availableMcpTools != null) {
            availableMcpTools.clear();
        }
    }
}