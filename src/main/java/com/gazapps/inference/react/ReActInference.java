package com.gazapps.inference.react;

import java.util.ArrayList;
import java.util.HashMap;
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
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

public class ReActInference implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(ReActInference.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final Llm llmService;
    private final int maxIterations;
    private final boolean debugMode;
    
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
        
        initializeAvailableMcpTools();
    }

    @Override
    public String processQuery(String query) {
        List<ReActStep> steps = new ArrayList<>();
        
        logger.info("[REACT-DEBUG] Starting ReAct processing for query: {}", query);
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (debugMode) {
                logger.info("ReAct Iteration {}/{}", iteration + 1, maxIterations);
            }
            
            // THOUGHT
            String thought = generateThought(query, steps);
            steps.add(ReActStep.thought(thought));
            
            logger.info("[REACT-DEBUG] Generated thought: {}", thought);
            
            // Check if complete
            if (thought.toUpperCase().contains("FINAL ANSWER:")) {
                String finalAnswer = extractFinalAnswer(thought);
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
                    
                    // OBSERVATION
                    String observation = executeFunction(decision.getToolName(), decision.getArgs());
                    steps.add(ReActStep.observation(observation));
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
        
        // Add available tools info
        if (availableMcpTools != null && !availableMcpTools.isEmpty()) {
            prompt.append("AVAILABLE TOOLS:\n");
            for (FunctionDeclaration tool : availableMcpTools) {
                prompt.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            }
            prompt.append("\n");
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
        
        prompt.append("AVAILABLE TOOLS:\n");
        if (availableMcpTools != null) {
            for (FunctionDeclaration tool : availableMcpTools) {
                prompt.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            }
        }
        
        prompt.append("\nRespond with the exact function call format:\n");
        prompt.append("FUNCTION_CALL:tool_name:{\"parameter\":\"value\"}\n");
        prompt.append("\nFor weather: FUNCTION_CALL:weather-nws_get-forecast:{\"latitude\":40.7128,\"longitude\":-74.0060}\n");
        prompt.append("Use your knowledge of world cities to get the correct coordinates.");
        
        FunctionDeclaration[] tools = availableMcpTools.toArray(new FunctionDeclaration[0]);
        
        logger.info("[REACT-DEBUG] Available tools count: {}", tools.length);
        if (tools.length > 0) {
            logger.info("[REACT-DEBUG] First tool: {}", tools[0].name);
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
        try {
            // Primeiro, tentar obter o nome com namespace
            String namespacedToolName = mcpServers.getNamespacedToolName(functionName);
            
            if (namespacedToolName == null) {
                // Fallback: talvez já seja um nome com namespace
                namespacedToolName = functionName;
            }
            
            // Usar a lógica original, mas com nome correto
            String serverName = mcpServers.getServerForTool(namespacedToolName);
            if (serverName == null) {
                return "Tool execution failed: Server not found for tool: " + functionName;
            }
            
            McpSyncClient client = mcpServers.getClient(serverName);
            String originalToolName = namespacedToolName.substring(serverName.length() + 1);
            String result = mcpService.executeToolByName(client, originalToolName, args);
            
            if (debugMode) {
                logger.info("[REACT-DEBUG] ACTION: {}({})", functionName, args);
                logger.info("[REACT-DEBUG] OBSERVATION: {}", result);
                
                // Extra debug for weather tool
                if (functionName.contains("weather")) {
                    logger.info("[REACT-DEBUG] Weather tool called with result length: {}", result != null ? result.length() : 0);
                    if (result != null && result.contains("error")) {
                        logger.warn("[REACT-DEBUG] Weather tool returned error: {}", result);
                    }
                }
            }
            
            return result;
        } catch (Exception e) {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            if (e.getMessage().contains("Access denied") || e.getMessage().contains("outside allowed directories")) {
                errorMsg += ". Remember to use only paths within C:\\Users\\gazol\\Documents";
            }
            logger.warn(errorMsg, e);
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
                ListToolsResult toolsResult = client.listTools();
                List<io.modelcontextprotocol.spec.McpSchema.Tool> serverTools = toolsResult.tools();
                for (io.modelcontextprotocol.spec.McpSchema.Tool mcpTool : serverTools) {
                    String toolName = mcpTool.name();
                    String namespacedToolName = serverName + "_" + toolName;
                    FunctionDeclaration geminiFunction = llmService.convertMcpToolToFunction(mcpTool, namespacedToolName);
                    availableMcpTools.add(geminiFunction);
                }
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
        
        // Add MCP servers info (simplified)
        if (mcpServers != null && !mcpServers.mcpServers.isEmpty()) {
            prompt.append("AVAILABLE TOOLS:\n");
            if (availableMcpTools != null) {
                for (FunctionDeclaration tool : availableMcpTools) {
                    prompt.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
                }
            }
            prompt.append("\n");
        }
        
        return prompt.toString();
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
