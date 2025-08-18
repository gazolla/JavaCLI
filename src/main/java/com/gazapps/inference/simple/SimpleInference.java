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
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Llm llmService;
    private final MCPInfo mcpInfo;
    private final Config config;
    
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
        if (debug) {
            logger.info("[SIMPLE] Processing query: {}", query);
        }

        try {
            // Step 1: Get all available tools
            List<Tool> allTools = mcpInfo.listAllTools();
            if (debug) {
                logger.info("[SIMPLE] Found {} total tools", allTools.size());
            }

            // Step 2: Single LLM call for analysis + decision (KISS approach)
            String analysisPrompt = buildAnalysisPrompt(query, allTools);
            String llmResponse = llmService.generateResponse(analysisPrompt, null);
            
            if (debug) {
                logger.info("[SIMPLE] LLM analysis response: {}", llmResponse);
            }

            // Step 3: Parse response - KISS format
            if (llmResponse.startsWith("TOOL:")) {
                // Execute tool and generate contextualized response
                return executeToolAndRespond(llmResponse, query);
            } else {
                // Direct response - no tools needed
                return llmResponse;
            }

        } catch (Exception e) {
            logger.error("[SIMPLE] Error processing query", e);
            return "I encountered an error while processing your request: " + e.getMessage();
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
            
            if (debug) {
                logger.info("[SIMPLE] Executing tool: {} with args: {}", toolName, args);
            }
            
            // Try to find the namespaced tool name using MCPServers reverse mapping
            String namespacedToolName = mcpInfo.getNamespacedToolName(toolName);
            
            if (namespacedToolName == null) {
                // Fallback: maybe it's already namespaced
                namespacedToolName = toolName;
            }
            
            if (debug) {
                logger.info("[SIMPLE] Resolved tool name: {} -> {}", toolName, namespacedToolName);
            }
            
            // Execute tool
            String toolResult = mcpInfo.executeTool(namespacedToolName, args);
            
            if (debug) {
                logger.info("[SIMPLE] Tool result: {}", toolResult);
            }
            
            // Generate contextualized response
            String finalPrompt = toolPromptTemplate
                .replace("{TOOL_NAME}", toolName)
                .replace("{RESULT}", toolResult)
                .replace("{QUERY}", originalQuery);
                
            String finalResponse = llmService.generateResponse(finalPrompt, null);
            
            if (debug) {
                logger.info("[SIMPLE] Final response: {}", finalResponse);
            }
            
            return finalResponse;
            
        } catch (Exception e) {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            if (debug) {
                logger.error("[SIMPLE] " + errorMsg, e);
            }
            return errorMsg;
        }
    }

    @Override
    public String buildSystemPrompt() {
        // KISS: Use the same analysis prompt format
        List<Tool> allTools = mcpInfo.listAllTools();
        return buildAnalysisPrompt("[System Prompt]", allTools);
    }

    @Override
    public void close() {
        // No resources to close in this simple implementation
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
