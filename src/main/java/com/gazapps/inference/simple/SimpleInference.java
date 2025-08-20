package com.gazapps.inference.simple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.mcp.MCPInfo;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;
import com.gazapps.mcp.ToolManager;
import com.gazapps.mcp.ToolManager.ToolOperationResult;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * SimpleInference - A streamlined, efficient inference system that demonstrates
 * how intelligent combination of existing MCPInfo capabilities with simple tool
 * selection logic and configurable prompts can deliver superior results with
 * minimal code and configuration.
 * 
 * This implementation proves that well-executed simplicity outperforms 
 * unnecessary complexity by leveraging the MCPInfo reverse mapping system
 * and Config infrastructure to create a performant, maintainable solution.
 */
public class SimpleInference implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(SimpleInference.class);
    private static final Logger conversationLogger = Config.getInferenceConversationLogger("simple");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Llm llmService;
    private final MCPInfo mcpInfo;
    private final Config config;
    private final ToolManager toolManager;
    
    // Configuration loaded from properties
    private final int maxTools;
    private final boolean debug;
    private final String systemPromptTemplate;
    private final String toolPromptTemplate;
    private Object conversationMemory;

    /**
     * Constructs SimpleInference following the standard pattern, receiving 
     * all required dependencies and loading configuration from properties.
     */
    public SimpleInference(Llm llmService, MCPService mcpService, MCPServers mcpServers, Map<String, Object> options) {
        this.llmService = Objects.requireNonNull(llmService, "LLM service is required");
        this.mcpInfo = new MCPInfo(
            Objects.requireNonNull(mcpServers, "MCP servers is required"), 
            Objects.requireNonNull(mcpService, "MCP service is required")
        );
        this.config = new Config();
        this.toolManager = new ToolManager(mcpInfo, mcpServers);
        
        // Load configuration from properties using Properties directly
        this.maxTools = Integer.parseInt(getConfigProperty("simple.max.tools", "5"));
        this.debug = Boolean.parseBoolean(getConfigProperty("simple.debug", "true"));
        this.systemPromptTemplate = getConfigProperty("simple.prompt.system", 
            "You are an AI assistant with access to tools. Analyze the user query and use the most relevant tools from the following list:\n\n{TOOLS}\n\nProvide accurate and helpful responses using the appropriate tools when needed.");
        this.toolPromptTemplate = getConfigProperty("simple.prompt.tool",
            "Based on the tool execution:\n\nTool: {TOOL_NAME}\nResult: {RESULT}\n\nUser Query: {QUERY}\n\nProvide a comprehensive response incorporating the tool result.");
        
        if (debug) {
            logger.info("[SIMPLE] Initialized with maxTools={}, debug={}", maxTools, debug);
        }
    }

    @Override
    public String getStrategyName() {
        return "simple";
    }
    
    public void setConversationMemory(Object memory) {
        this.conversationMemory = memory;
    }

    @Override
    public String processQuery(String query) {
        // Log da query inicial do usuário
        conversationLogger.info("USER: {}", cleanUserQuery(query));
        
        if (debug) {
            logger.info("[SIMPLE] Processing query: {}", query);
        }

        try {
            // Step 1: Get all available tools
            List<Tool> allTools = mcpInfo.listAllTools();
            if (debug) {
                logger.info("[SIMPLE] Found {} total tools", allTools.size());
            }

            // Single LLM call using ToolManager
            String analysisPrompt = buildAnalysisPrompt(query, toolManager.getAvailableTools());
            String llmResponse = llmService.generateResponse(analysisPrompt, null);
            
            // Log da análise
            conversationLogger.info("ANALYSIS: {}", cleanAnalysis(llmResponse));
            
            if (debug) {
                logger.info("[SIMPLE] LLM analysis response: {}", llmResponse);
            }

            // Step 3: Parse response - KISS format
            if (llmResponse.startsWith("TOOL:")) {
                // Execute tool and generate contextualized response
                return executeToolAndRespond(llmResponse, query);
            } else {
                // Direct response - no tools needed
                conversationLogger.info("ASSISTANT: {}", cleanResponse(llmResponse));
                return llmResponse;
            }

        } catch (Exception e) {
            String errorMsg = "I encountered an error while processing your request: " + e.getMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            logger.error("[SIMPLE] Error processing query", e);
            return errorMsg;
        }
    }

    /**
     * KISS: Single prompt for analysis + decision
     * Format: "TOOL:tool_name:{json}" or direct response
     */
    private String buildAnalysisPrompt(String query, List<Tool> allTools) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Analyze this user query and decide if any tool is needed.\n\n");
        prompt.append("USER QUERY: ").append(query).append("\n\n");
        
        prompt.append("AVAILABLE TOOLS:\n");
        for (Tool tool : allTools) {
            prompt.append("- ").append(tool.name()).append(": ").append(tool.description());
            
            // Add parameter information for better LLM understanding
            if (tool.inputSchema() != null && tool.inputSchema().properties() != null) {
                prompt.append(" [Parameters: ");
                Map<String, Object> properties = tool.inputSchema().properties();
                List<String> required = tool.inputSchema().required();
                
                List<String> paramDescriptions = new ArrayList<>();
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> paramDetails = (Map<String, Object>) entry.getValue();
                    String paramType = (String) paramDetails.get("type");
                    String paramDesc = (String) paramDetails.get("description");
                    
                    String paramInfo = paramName + "(" + paramType + ")";
                    if (required != null && required.contains(paramName)) {
                        paramInfo += "*required*";
                    }
                    if (paramDesc != null && !paramDesc.isEmpty()) {
                        paramInfo += ": " + paramDesc;
                    }
                    paramDescriptions.add(paramInfo);
                }
                prompt.append(String.join(", ", paramDescriptions));
                prompt.append("]");
            }
            prompt.append("\n");
        }
        
        prompt.append("\nINSTRUCTIONS:\n");
        prompt.append("- If you need to use a tool, respond with: TOOL:tool_name:{\"parameter\":\"value\"}\n");
        prompt.append("- If no tool is needed, respond directly to the user query\n");
        prompt.append("- For Brazil/Brasília time queries, ALWAYS use timezone 'America/Sao_Paulo' (never 'America/Brasilia')\n");
        prompt.append("- Example: For 'Que dia é hoje em Brasília?' respond: TOOL:get_current_time:{\"timezone\":\"America/Sao_Paulo\"}\n");
        prompt.append("- For weather queries, use get-forecast with latitude and longitude (e.g., NYC: lat=40.7128, lon=-74.0060)\n");
        prompt.append("- For file operations, use filesystem tools with proper path parameter\n");
        prompt.append("- Always include ALL required parameters as shown in the tool descriptions\n\n");
        
        prompt.append("RESPONSE:");
        
        return prompt.toString();
    }
    
    /**
     * KISS: Execute tool and respond in one method
     */
    private String executeToolAndRespond(String toolResponse, String originalQuery) {
        try {
            // Parse TOOL:name:{json} format
            String[] parts = toolResponse.substring(5).split(":", 2); // Remove "TOOL:" prefix
            String toolName = parts[0];
            String argsJson = parts.length > 1 ? parts[1] : "{}";
            
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            
            // Log da execução da ferramenta
            conversationLogger.info("TOOL_CALL: {}({})", toolName, formatArgs(args));
            
            if (debug) {
                logger.info("[SIMPLE] Executing tool: {} with args: {}", toolName, args);
            }
            
            // Use ToolManager for validation and execution
            ToolOperationResult result = toolManager.validateAndExecute(toolName, args);
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                    errorMsg += ". Try: " + String.join(", ", result.getSuggestions());
                }
                conversationLogger.info("ERROR: {}", errorMsg);
                return errorMsg;
            }
            
            String toolResult = result.getResult();
            
            // Log do resultado da ferramenta
            conversationLogger.info("TOOL_RESULT: {}", cleanToolResult(toolResult));
            
            if (debug) {
                logger.info("[SIMPLE] Tool result: {}", toolResult);
            }
            
            // Generate contextualized response
            String finalPrompt = toolPromptTemplate
                .replace("{TOOL_NAME}", toolName)
                .replace("{RESULT}", toolResult)
                .replace("{QUERY}", originalQuery);
                
            String finalResponse = llmService.generateResponse(finalPrompt, null);
            
            // Log da resposta final
            conversationLogger.info("ASSISTANT: {}", cleanResponse(finalResponse));
            
            if (debug) {
                logger.info("[SIMPLE] Final response: {}", finalResponse);
            }
            
            return finalResponse;
            
        } catch (Exception e) {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            if (debug) {
                logger.error("[SIMPLE] " + errorMsg, e);
            }
            return errorMsg;
        }
    }

    @Override
    public String buildSystemPrompt() {
        // KISS: Use ToolManager for available tools
        return buildAnalysisPrompt("[System Prompt]", toolManager.getAvailableTools());
    }

    @Override
    public void close() {
        // No resources to close in this simple implementation
    }
    
    /**
     * Limpa a query do usuário removendo prompts de sistema
     */
    private String cleanUserQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "[EMPTY_QUERY]";
        }
        
        return query.length() > 200 ? query.substring(0, 200) + "..." : query;
    }
    
    /**
     * Limpa a análise da LLM
     */
    private String cleanAnalysis(String analysis) {
        if (analysis == null || analysis.trim().isEmpty()) {
            return "[EMPTY_ANALYSIS]";
        }
        
        // Se é uma chamada de ferramenta, simplifica
        if (analysis.startsWith("TOOL:")) {
            return "Need to use tool";
        }
        
        return analysis.length() > 300 ? analysis.substring(0, 300) + "..." : analysis;
    }
    
    /**
     * Limpa resposta removendo informações técnicas
     */
    private String cleanResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "[EMPTY_RESPONSE]";
        }
        
        return response.length() > 500 ? response.substring(0, 500) + "..." : response;
    }
    
    /**
     * Limpa resultado de ferramenta
     */
    private String cleanToolResult(String result) {
        if (result == null || result.trim().isEmpty()) {
            return "[EMPTY_RESULT]";
        }
        
        return result.length() > 400 ? result.substring(0, 400) + "..." : result;
    }
    
    /**
     * Formata argumentos para log
     */
    private String formatArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return args.toString();
        }
    }

    /**
     * Helper method to access Config properties using reflection to access the private getProperty method.
     */
    private String getConfigProperty(String key, String defaultValue) {
        try {
            // Access the private properties field
            java.lang.reflect.Field propertiesField = config.getClass().getDeclaredField("properties");
            propertiesField.setAccessible(true);
            java.util.Properties properties = (java.util.Properties) propertiesField.get(config);
            return properties.getProperty(key, defaultValue);
        } catch (Exception e) {
            if (debug) {
                logger.warn("[SIMPLE] Could not access property {}: {}", key, e.getMessage());
            }
            return defaultValue;
        }
    }
}
