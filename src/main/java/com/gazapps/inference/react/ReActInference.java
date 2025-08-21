package com.gazapps.inference.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmResponse;
import com.gazapps.llm.tool.ToolDefinition;
import com.gazapps.mcp.MCPInfo;
import com.gazapps.mcp.MCPServers;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.ToolManager;
import com.gazapps.mcp.ToolManager.ToolOperationResult;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * ReActInference refatorada para usar a nova interface Llm.
 * Remove todo acoplamento específico com implementações de LLM.
 */
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
    
    private List<ToolDefinition> availableToolDefinitions;
    private Object conversationMemory;
    
    @Override
    public ChatEngineBuilder.InferenceStrategy getStrategyName() {
        return ChatEngineBuilder.InferenceStrategy.REACT;
    }

    public ReActInference(Llm llmService, MCPService mcpService, MCPServers mcpServers, Map<String, Object> options) {
        this.llmService = Objects.requireNonNull(llmService);
        this.mcpService = Objects.requireNonNull(mcpService);
        this.mcpServers = Objects.requireNonNull(mcpServers);
        this.maxIterations = (Integer) options.getOrDefault("maxIterations", 10);
        this.debugMode = (Boolean) options.getOrDefault("debug", false);
        this.toolManager = new ToolManager(new MCPInfo(mcpServers, mcpService), mcpServers);
        
        initializeAvailableTools();
        
        if (debugMode) {
            logger.info("[REACT] Initialized with LLM provider: {}, capabilities: {}", 
                       llmService.getProviderName(), llmService.getCapabilities());
        }
    }

    @Override
    public String processQuery(String query) {
        List<ReActStep> steps = new ArrayList<>();
        
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
                    
                    conversationLogger.info("ACTION_{}: {}({})", iteration + 1, 
                                          decision.getToolName(), formatArgs(decision.getArgs()));
                    
                    // OBSERVATION
                    String observation = executeTool(decision.getToolName(), decision.getArgs());
                    steps.add(ReActStep.observation(observation));
                    
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

    private void initializeAvailableTools() {
        try {
            List<Tool> mcpTools = toolManager.getMcpInfo().listAllTools();
            this.availableToolDefinitions = llmService.convertMcpTools(mcpTools);
            
            if (debugMode) {
                logger.info("[REACT] Initialized {} tools", availableToolDefinitions.size());
            }
        } catch (Exception e) {
            logger.error("[REACT] Failed to initialize tools", e);
            this.availableToolDefinitions = new ArrayList<>();
        }
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
        
        // Add available tools info using ToolDefinitions
        String toolsInfo = formatToolsInfo();
        
        if (debugMode && toolsInfo != null) {
            logger.info("[REACT-DEBUG] Tools info length: {}", toolsInfo.length());
        }
        
        prompt.append("AVAILABLE TOOLS:\n").append(toolsInfo).append("\n\n");
        
        prompt.append("Now think about the current situation and what to do next.\n");
        prompt.append("Format: THOUGHT: [your reasoning]\n");
        prompt.append("If you need to act: ACTION: [tool_name] with arguments [args]\n");
        prompt.append("If you have the final answer: FINAL ANSWER: [answer]\n\n");
        prompt.append("THOUGHT:");

        try {
            LlmResponse response = llmService.generateResponse(prompt.toString());
            
            if (!response.isSuccess()) {
                logger.error("[REACT] Failed to generate thought: {}", response.getErrorMessage());
                return "Error generating thought: " + response.getErrorMessage();
            }
            
            return response.getContent();
            
        } catch (Exception e) {
            logger.error("[REACT] Exception in generateThought", e);
            return "Error: " + e.getMessage();
        }
    }
    
    private String formatToolsInfo() {
        if (availableToolDefinitions == null || availableToolDefinitions.isEmpty()) {
            return "No tools available";
        }
        
        StringBuilder toolsInfo = new StringBuilder();
        for (ToolDefinition tool : availableToolDefinitions) {
            toolsInfo.append("- ").append(tool.getName())
                    .append(": ").append(tool.getDescription());
                    
            if (!tool.getParameters().isEmpty()) {
                toolsInfo.append(" [Parameters: ");
                List<String> paramInfo = new ArrayList<>();
                
                for (Map.Entry<String, Object> param : tool.getParameters().entrySet()) {
                    String paramName = param.getKey();
                    Map<String, Object> paramDetails = (Map<String, Object>) param.getValue();
                    String paramType = (String) paramDetails.get("type");
                    
                    String info = paramName + "(" + paramType + ")";
                    if (tool.getRequired().contains(paramName)) {
                        info += "*required*";
                    }
                    paramInfo.add(info);
                }
                
                toolsInfo.append(String.join(", ", paramInfo)).append("]");
            }
            toolsInfo.append("\n");
        }
        
        return toolsInfo.toString();
    }

    private ActionDecision decideAction(String thought) {
        try {
            String prompt = buildActionDecisionPrompt(thought);
            
            LlmResponse response = llmService.generateResponse(prompt);
            
            if (!response.isSuccess()) {
                logger.error("[REACT] Failed to decide action: {}", response.getErrorMessage());
                return ActionDecision.noAction();
            }
            
            return parseActionDecision(response.getContent());
            
        } catch (Exception e) {
            logger.error("[REACT] Exception in decideAction", e);
            return ActionDecision.noAction();
        }
    }

    private String buildActionDecisionPrompt(String thought) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Based on this thought, decide if you need to take an action:\n\n");
        prompt.append("THOUGHT: ").append(thought).append("\n\n");
        
        prompt.append("AVAILABLE TOOLS:\n");
        prompt.append(formatToolsInfo()).append("\n");
        
        prompt.append("If you need to use a tool, respond with:\n");
        prompt.append("ACTION: tool_name\n");
        prompt.append("ARGS: {\"param1\": \"value1\", \"param2\": \"value2\"}\n\n");
        prompt.append("If no action is needed, respond with:\n");
        prompt.append("NO ACTION\n\n");
        
        prompt.append("Response:");
        
        return prompt.toString();
    }

    private ActionDecision parseActionDecision(String response) {
        try {
            if (response.toUpperCase().contains("NO ACTION")) {
                return ActionDecision.noAction();
            }
            
            String[] lines = response.split("\n");
            String toolName = null;
            Map<String, Object> args = null;
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("ACTION:")) {
                    toolName = line.substring(7).trim();
                } else if (line.startsWith("ARGS:")) {
                    String argsJson = line.substring(5).trim();
                    args = objectMapper.readValue(argsJson, Map.class);
                }
            }
            
            if (toolName != null) {
                return ActionDecision.action(toolName, args != null ? args : Map.of());
            } else {
                return ActionDecision.noAction();
            }
            
        } catch (Exception e) {
            logger.error("[REACT] Failed to parse action decision: {}", response, e);
            return ActionDecision.noAction();
        }
    }

    private String executeTool(String toolName, Map<String, Object> args) {
        try {
            if (debugMode) {
                logger.info("[REACT] Executing tool: {} with args: {}", toolName, args);
            }
            
            ToolOperationResult result = toolManager.validateAndExecute(toolName, args);
            
            if (result.isSuccess()) {
                return result.getResult();
            } else {
                String errorMsg = result.getErrorMessage();
                if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                    errorMsg += ". Suggestions: " + String.join(", ", result.getSuggestions());
                }
                return "Error: " + errorMsg;
            }
            
        } catch (Exception e) {
            logger.error("[REACT] Exception executing tool: {}", toolName, e);
            return "Tool execution failed: " + e.getMessage();
        }
    }

    private String extractFinalAnswer(String thought) {
        int index = thought.toUpperCase().indexOf("FINAL ANSWER:");
        if (index != -1) {
            return thought.substring(index + 13).trim();
        }
        return thought;
    }

    public void setConversationMemory(Object memory) {
        this.conversationMemory = memory;
    }

    @Override
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a ReAct (Reasoning + Acting) agent. ");
        prompt.append("Think step by step and use available tools when needed.\n\n");
        prompt.append("Available Tools:\n");
        prompt.append(formatToolsInfo());
        return prompt.toString();
    }

    @Override
    public void close() {
        // No resources to close
    }
    
    // Helper methods for logging and cleaning
    
    private String cleanUserQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "[EMPTY_QUERY]";
        }
        return query.length() > 200 ? query.substring(0, 200) + "..." : query;
    }
    
    private String cleanThought(String thought) {
        if (thought == null || thought.trim().isEmpty()) {
            return "[EMPTY_THOUGHT]";
        }
        return thought.length() > 300 ? thought.substring(0, 300) + "..." : thought;
    }
    
    private String cleanObservation(String observation) {
        if (observation == null || observation.trim().isEmpty()) {
            return "[EMPTY_OBSERVATION]";
        }
        return observation.length() > 400 ? observation.substring(0, 400) + "..." : observation;
    }
    
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
}
