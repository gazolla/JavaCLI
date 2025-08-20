package com.gazapps.inference.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.mcp.MCPInfo;
import com.gazapps.mcp.MCPServers;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.ToolManager;
import com.gazapps.mcp.ToolManager.ToolOperationResult;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class ReActInference implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(ReActInference.class);
    private static final Logger conversationLogger = Config.getInferenceConversationLogger("react");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final Llm llmService;
    private final int maxIterations;
    private final boolean debugMode;
    private final ToolManager toolManager;
    
    private List<FunctionDeclaration> availableMcpTools;
    private Object conversationMemory;
    
    @Override
    public String getStrategyName() {
        return "react";
    }


    public ReActInference(Llm llmService, MCPService mcpService, MCPServers mcpServers, Map<String, Object> options) {
        this.llmService = Objects.requireNonNull(llmService);
        this.mcpService = Objects.requireNonNull(mcpService);
        this.mcpServers = Objects.requireNonNull(mcpServers);
        this.maxIterations = (Integer) options.getOrDefault("maxIterations", 10);
        this.debugMode = (Boolean) options.getOrDefault("debug", false);
        this.toolManager = new ToolManager(new MCPInfo(mcpServers, mcpService), mcpServers);
        
        initializeAvailableMcpTools();
    }

    @Override
    public String processQuery(String query) {
        List<ReActStep> steps = new ArrayList<>();
        
        // Log da query inicial do usuário
        conversationLogger.info("USER: {}", cleanUserQuery(query));
        
        logger.info("[REACT-DEBUG] Starting ReAct processing for query: {}", query);
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (debugMode) {
                logger.info("ReAct Iteration {}/{}", iteration + 1, maxIterations);
            }
            
            // THOUGHT
            String thought = generateThought(query, steps);
            steps.add(ReActStep.thought(thought));
            
            String cleanThought = cleanThought(thought);
            conversationLogger.info("THOUGHT_{}: {}", iteration + 1, cleanThought);
            
            logger.info("[REACT-DEBUG] Generated thought: {}", thought);
            
            // Check if complete
            if (thought.toUpperCase().contains("FINAL ANSWER:")) {
                String finalAnswer = extractFinalAnswer(thought);
                conversationLogger.info("ASSISTANT: {}", finalAnswer);
                logger.info("[REACT-DEBUG] Extracted final answer: {}", finalAnswer);
                return finalAnswer;
            }
            
            // ACTION (if needed)
            String thoughtUpper = thought.toUpperCase();
            if (thoughtUpper.contains("NEED ACTION:") || thoughtUpper.contains("ACTION:") || 
                thoughtUpper.contains("USE TOOL") || thoughtUpper.contains("CALL TOOL")) {
                    
                logger.info("[REACT-DEBUG] Deciding action based on thought");
                ActionDecision decision = decideAction(thought);
                if (decision.shouldAct()) {
                    steps.add(ReActStep.action(decision.getToolName(), decision.getArgs()));
                    
                    // Log da ação
                    conversationLogger.info("ACTION_{}: {}({})", iteration + 1, 
                                          decision.getToolName(), formatArgs(decision.getArgs()));
                    
                    // OBSERVATION
                    String observation = executeFunction(decision.getToolName(), decision.getArgs());
                    steps.add(ReActStep.observation(observation));
                    
                    // Log da observação
                    conversationLogger.info("OBSERVATION_{}: {}", iteration + 1, cleanObservation(observation));
                } else {
                    logger.info("[REACT-DEBUG] Decision was not to act");
                }
            } else {
                logger.info("[REACT-DEBUG] No action keywords found in thought");
            }
        }
        
        String fallbackResult = "Reached maximum iterations. Last thoughts: " + 
               steps.stream().filter(s -> s.getType() == ReActStep.StepType.THOUGHT)
                    .map(ReActStep::getContent).collect(Collectors.joining("; "));
                    
        conversationLogger.info("ASSISTANT: {}", fallbackResult);
        logger.info("[REACT-DEBUG] Returning fallback result: {}", fallbackResult);
        
        return fallbackResult;
    }

    private String generateThought(String originalQuery, List<ReActStep> steps) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are in a ReAct (Reasoning + Acting) cycle. Think step by step.\n\n");
        prompt.append("ORIGINAL QUESTION: ").append(originalQuery).append("\n\n");
        
        if (!steps.isEmpty()) {
            prompt.append("EXECUTION HISTORY:\n");
            for (ReActStep step : steps) {
                prompt.append(step.toString()).append("\n");
            }
            prompt.append("\n");
        }
        
        // Add available tools info dynamically
        String toolsInfo = toolManager.getFormattedToolInfo();
        
        if (debugMode) {
            logger.info("[REACT-DEBUG] ToolManager.getFormattedToolInfo() returned: {}", 
                toolsInfo != null ? toolsInfo.substring(0, Math.min(200, toolsInfo.length())) + "..." : "NULL");
        }
        
        if (toolsInfo != null && !toolsInfo.trim().equals("AVAILABLE TOOLS:") && !toolsInfo.trim().isEmpty()) {
            prompt.append(toolsInfo).append("\n");
        } else {
            // Fallback: get tools directly from MCPInfo
            List<Tool> allTools = new MCPInfo(mcpServers, mcpService).listAllTools();
            if (debugMode) {
                logger.info("[REACT-DEBUG] Fallback: MCPInfo.listAllTools() returned {} tools", allTools.size());
            }
            if (!allTools.isEmpty()) {
                prompt.append("AVAILABLE TOOLS:\n");
                for (Tool tool : allTools) {
                    prompt.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
                }
                prompt.append("\n");
            }
        }
        
        prompt.append("""
            Now think about what to do next:
            - If you need to get weather information, use weather tools
            - IMPORTANT: Weather tools require latitude and longitude coordinates
            - Use your knowledge: NYC (40.7128, -74.0060), London (51.5074, -0.1278), etc.
            - If you need to create/write files, use filesystem tools  
            - IMPORTANT: All files must be created in C:\\Users\\gazol\\Documents directory
            - If you have enough information to answer, start your response with "FINAL ANSWER:"
            - If you need to use a tool, start your response with "NEED ACTION:"
            
            Be explicit about your reasoning. What do you need to do?
            
            THOUGHT:
            """);
        
        String thought = llmService.generateResponse(prompt.toString(), null);
        
        if (debugMode) {
            logger.info("[REACT-DEBUG] THOUGHT: {}", thought);
        }
        
        return thought;
    }

    private ActionDecision decideAction(String thought) {
        String thoughtUpper = thought.toUpperCase();
        
        // Check if should act
        if (!thoughtUpper.contains("NEED ACTION:") && !thoughtUpper.contains("ACTION:") && 
            !thoughtUpper.contains("USE TOOL") && !thoughtUpper.contains("CALL TOOL")) {
            return ActionDecision.noAction();
        }
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("Based on your thinking, choose and execute the appropriate action.\n\n");
        prompt.append("YOUR THOUGHT: ").append(thought).append("\n\n");
        
        // Add explicit tool guidance
        prompt.append("TOOL SELECTION GUIDANCE:\n");
        prompt.append("- For weather information: use weather-nws tools\n");
        prompt.append("- IMPORTANT: Weather tools require latitude and longitude coordinates (use your knowledge of world cities)\n");
        prompt.append("- Example: For NYC use latitude=40.7128, longitude=-74.0060\n");
        prompt.append("- Example: For London use latitude=51.5074, longitude=-0.1278\n");
        prompt.append("- For creating/writing files: use filesystem tools\n");
        prompt.append("- IMPORTANT: Files must be created in C:\\Users\\gazol\\Documents (this is the only allowed directory)\n");
        prompt.append("- For memory/storage: use memory tools\n\n");
        
        String toolsInfo = toolManager.getFormattedToolInfo();
        if (toolsInfo != null && !toolsInfo.trim().equals("AVAILABLE TOOLS:") && !toolsInfo.trim().isEmpty()) {
            prompt.append(toolsInfo);
        } else {
            // Fallback: get tools directly from MCPInfo
            List<Tool> allTools = new MCPInfo(mcpServers, mcpService).listAllTools();
            if (!allTools.isEmpty()) {
                prompt.append("AVAILABLE TOOLS:\n");
                for (Tool tool : allTools) {
                    prompt.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
                }
            }
        }
        
        prompt.append("\nRespond with the exact function call format:\n");
        prompt.append("FUNCTION_CALL:tool_name:{\"parameter\":\"value\"}\n");
        prompt.append("\nFor weather: FUNCTION_CALL:weather-nws_get-forecast:{\"latitude\":40.7128,\"longitude\":-74.0060}\n");
        prompt.append("Use your knowledge of world cities to get the correct coordinates.");
        
        FunctionDeclaration[] tools = availableMcpTools != null ? availableMcpTools.toArray(new FunctionDeclaration[0]) : new FunctionDeclaration[0];
        
        logger.info("[REACT-DEBUG] Available tools for LLM: {} tools", tools.length);
        if (tools.length > 0) {
            logger.info("[REACT-DEBUG] First tool for LLM: {}", tools[0].name);
        }
        
        String actionResponse = llmService.generateResponse(prompt.toString(), List.of(tools));
        
        if (actionResponse == null || actionResponse.trim().isEmpty()) {
            logger.warn("[REACT-DEBUG] LLM returned empty response for function calling! Trying without tools...");
            actionResponse = llmService.generateResponse(
                prompt.toString() + 
                "\n\nPlease respond with: FUNCTION_CALL:weather-nws_get-forecast:{\"latitude\":LATITUDE,\"longitude\":LONGITUDE}\n" +
                "Use your knowledge to provide the correct coordinates for the city mentioned in the thought.", 
                null
            );
        }
        
        if (debugMode) {
            logger.info("[REACT-DEBUG] ACTION PROMPT: {}", prompt.toString());
            logger.info("[REACT-DEBUG] ACTION RESPONSE: {}", actionResponse);
        }
        
        return parseActionResponse(actionResponse);
    }

    private ActionDecision parseActionResponse(String response) {
        logger.info("[REACT-DEBUG] Parsing action response: '{}'", response);
        
        if (response == null || response.trim().isEmpty()) {
            logger.warn("[REACT-DEBUG] Response is null or empty");
            return ActionDecision.noAction();
        }
        
        try {
            // Check FUNCTION_CALL format
            if (response.startsWith("FUNCTION_CALL:")) {
                String[] parts = response.split(":", 3);
                if (parts.length >= 3) {
                    String functionName = parts[1];
                    String argsJson = parts[2];
                    Map<String, Object> arguments = objectMapper.readValue(argsJson, Map.class);
                    logger.info("[REACT-DEBUG] Parsed FUNCTION_CALL: {}({})", functionName, arguments);
                    return ActionDecision.action(functionName, arguments);
                }
            }
            
            // Check JSON array format
            if (response.trim().startsWith("[")) {
                JsonNode jsonArray = objectMapper.readTree(response.trim());
                if (jsonArray.isArray() && jsonArray.size() > 0) {
                    JsonNode firstCall = jsonArray.get(0);
                    if (firstCall.has("name") && firstCall.has("parameters")) {
                        String functionName = firstCall.get("name").asText();
                        JsonNode paramsNode = firstCall.get("parameters");
                        Map<String, Object> arguments = objectMapper.convertValue(paramsNode, Map.class);
                        logger.info("[REACT-DEBUG] Parsed JSON array: {}({})", functionName, arguments);
                        return ActionDecision.action(functionName, arguments);
                    }
                }
            }
            
           
        } catch (Exception e) {
            logger.warn("[REACT-DEBUG] Error parsing action response: {}", e.getMessage());
        }
        
        logger.warn("[REACT-DEBUG] Could not parse action response, returning noAction");
        return ActionDecision.noAction();
    }

    private String extractFinalAnswer(String thought) {
        logger.info("[REACT-DEBUG] Extracting final answer from: {}", thought);
        
        if (thought == null || thought.trim().isEmpty()) {
            logger.warn("[REACT-DEBUG] Thought is null or empty");
            return "No final answer could be extracted";
        }
        
        // Look for FINAL ANSWER: and extract everything after it
        String upperThought = thought.toUpperCase();
        int finalAnswerIndex = upperThought.indexOf("FINAL ANSWER:");
        
        if (finalAnswerIndex != -1) {
            // Extract everything after "FINAL ANSWER:"
            String answer = thought.substring(finalAnswerIndex + "FINAL ANSWER:".length()).trim();
            logger.info("[REACT-DEBUG] Extracted answer: {}", answer);
            return answer.isEmpty() ? thought : answer;
        }
        
        // Fallback: look line by line (old method)
        String[] lines = thought.split("\\n");
        for (String line : lines) {
            if (line.toUpperCase().contains("FINAL ANSWER:")) {
                String answer = line.substring(line.toUpperCase().indexOf("FINAL ANSWER:") + "FINAL ANSWER:".length()).trim();
                logger.info("[REACT-DEBUG] Extracted answer from line: {}", answer);
                return answer.isEmpty() ? thought : answer;
            }
        }
        
        logger.info("[REACT-DEBUG] No FINAL ANSWER found, returning full thought");
        return thought;
    }

    private String executeFunction(String functionName, Map<String, Object> args) {
        ToolOperationResult result = toolManager.validateAndExecute(functionName, args);
        
        if (debugMode) {
            logger.info("[REACT-DEBUG] ACTION: {}({})", functionName, args);
            logger.info("[REACT-DEBUG] OBSERVATION: {}", result.isSuccess() ? result.getResult() : result.getErrorMessage());
        }
        
        if (result.isSuccess()) {
            return result.getResult();
        } else {
            String errorMsg = result.getErrorMessage();
            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                errorMsg += ". Try these alternatives: " + String.join(", ", result.getSuggestions());
            }
            return errorMsg;
        }
    }

    private void initializeAvailableMcpTools() {
        if (mcpServers == null) return;
        
        try {
            availableMcpTools = new ArrayList<>();
            
            for (Map.Entry<String, McpSyncClient> entry : mcpServers.mcpClients.entrySet()) {
                String serverName = entry.getKey();
                McpSyncClient client = entry.getValue();
                
                try {
                    ListToolsResult toolsResult = client.listTools();
                    List<io.modelcontextprotocol.spec.McpSchema.Tool> serverTools = toolsResult.tools();
                    
                    for (io.modelcontextprotocol.spec.McpSchema.Tool mcpTool : serverTools) {
                        String toolName = mcpTool.name();
                        String namespacedToolName = serverName + "_" + toolName;
                        
                        try {
                            FunctionDeclaration geminiFunction = llmService.convertMcpToolToFunction(mcpTool, namespacedToolName);
                            if (geminiFunction != null) {
                                availableMcpTools.add(geminiFunction);
                                if (debugMode) {
                                    logger.info("[REACT-DEBUG] Added tool: {} -> {}", toolName, namespacedToolName);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("[REACT-DEBUG] Failed to convert tool {}: {}", toolName, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[REACT-DEBUG] Failed to get tools from server {}: {}", serverName, e.getMessage());
                }
            }
            
            if (debugMode) {
                logger.info("[REACT-DEBUG] Initialized {} MCP tools for LLM", availableMcpTools.size());
            }
            
        } catch (Exception e) {
            logger.error("Erro ao inicializar ferramentas MCP", e);
            availableMcpTools = new ArrayList<>();
        }
    }

    public void setConversationMemory(Object memory) {
        this.conversationMemory = memory;
    }

    @Override
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("""
            You are a ReAct (Reasoning + Acting) agent. You work in cycles of THOUGHT → ACTION → OBSERVATION.
            
            IMPORTANT RULES:
            - Think before acting
            - Act only when necessary  
            - Continue until you have enough information to answer
            - Use "NEED ACTION:" when you need tools
            - Use "FINAL ANSWER:" when you can answer
            
            """);
        
        // Add available tools info
        String toolsInfo = toolManager.getFormattedToolInfo();
        if (toolsInfo != null && !toolsInfo.trim().equals("AVAILABLE TOOLS:") && !toolsInfo.trim().isEmpty()) {
            prompt.append(toolsInfo).append("\n");
        } else {
            // Fallback: get tools directly from MCPInfo  
            List<Tool> allTools = new MCPInfo(mcpServers, mcpService).listAllTools();
            if (!allTools.isEmpty()) {
                prompt.append("AVAILABLE TOOLS:\n");
                for (Tool tool : allTools) {
                    prompt.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
                }
                prompt.append("\n");
            }
        }
        
        return prompt.toString();
    }

    @Override
    public void close() {
        // Nothing to close
    }
    
    /**
     * Limpa a query do usuário removendo prompts de sistema
     */
    private String cleanUserQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "[EMPTY_QUERY]";
        }
        
        // Se é muito longo e contém markers de sistema, extrair query real
        if (query.length() > 500 && containsSystemPromptMarkers(query)) {
            String[] lines = query.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && 
                    !line.startsWith("You are") &&
                    !line.startsWith("AVAILABLE TOOLS") &&
                    !line.startsWith("INSTRUCTIONS") &&
                    !line.startsWith("USER QUERY:") &&
                    !line.contains("ORIGINAL QUESTION:") &&
                    !line.contains("THOUGHT:") &&
                    !line.contains("ACTION:")) {
                    return line.length() > 200 ? line.substring(0, 200) + "..." : line;
                }
            }
        }
        
        return query.length() > 200 ? query.substring(0, 200) + "..." : query;
    }
    
    /**
     * Limpa o pensamento removendo prefixos técnicos
     */
    private String cleanThought(String thought) {
        if (thought == null || thought.trim().isEmpty()) {
            return "[EMPTY_THOUGHT]";
        }
        
        // Remove prefixos comuns
        String cleaned = thought;
        if (cleaned.startsWith("THOUGHT:")) {
            cleaned = cleaned.substring("THOUGHT:".length()).trim();
        }
        
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "..." : cleaned;
    }
    
    /**
     * Limpa observação truncando se muito longa
     */
    private String cleanObservation(String observation) {
        if (observation == null || observation.trim().isEmpty()) {
            return "[EMPTY_OBSERVATION]";
        }
        
        return observation.length() > 400 ? observation.substring(0, 400) + "..." : observation;
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
     * Verifica se contém marcadores de prompt de sistema
     */
    private boolean containsSystemPromptMarkers(String text) {
        String[] markers = {
            "You are", "AVAILABLE TOOLS", "INSTRUCTIONS", "ReAct", 
            "EXECUTION HISTORY", "TOOL SELECTION GUIDANCE"
        };
        
        String upperText = text.toUpperCase();
        for (String marker : markers) {
            if (upperText.contains(marker.toUpperCase())) {
                return true;
            }
        }
        
        return false;
    }
}
