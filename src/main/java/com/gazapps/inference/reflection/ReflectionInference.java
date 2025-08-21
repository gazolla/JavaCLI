package com.gazapps.inference.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.core.ConversationMemory;
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
 * ReflectionInference refatorada para usar a nova interface Llm.
 * Implementa o padrão Reflection para auto-avaliação e melhoria iterativa de respostas.
 * 
 * O processo funciona em ciclos:
 * 1. Gera resposta inicial
 * 2. Avalia qualidade da resposta
 * 3. Se score < threshold, gera versão melhorada
 * 4. Repete até score satisfatório ou max iterações
 */
public class ReflectionInference implements Inference, AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ReflectionInference.class);
    private static final Logger conversationLogger = Config.getInferenceConversationLogger("reflection");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Llm llm;
    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final ToolManager toolManager;
    private final int maxIterations;
    private final double scoreThreshold;
    private final boolean debug;
    
    private ConversationMemory conversationMemory;
    private final List<ReflectionStep> currentSteps;
    private final MCPInfo mcpInfo;
    private List<ToolDefinition> availableTools;
    
    @Override
    public ChatEngineBuilder.InferenceStrategy getStrategyName() {
        return ChatEngineBuilder.InferenceStrategy.REFLECTION;
    }

    public ReflectionInference(Llm llm, MCPService mcpService, MCPServers mcpServers, 
                              int maxIterations, boolean debug) {
        this.llm = llm;
        this.mcpService = mcpService;
        this.mcpServers = mcpServers;
        this.toolManager = new ToolManager(new MCPInfo(mcpServers, mcpService), mcpServers);
        this.maxIterations = maxIterations > 0 ? maxIterations : ReflectionCriteria.DEFAULT_MAX_ITERATIONS;
        this.scoreThreshold = ReflectionCriteria.DEFAULT_SCORE_THRESHOLD;
        this.debug = debug;
        this.currentSteps = new ArrayList<>();
        this.mcpInfo = new MCPInfo(mcpServers, mcpService);
        this.availableTools = initializeTools();
        
        if (debug) {
            logger.info("[REFLECTION] Initialized with LLM provider: {}, capabilities: {}", 
                       llm.getProviderName(), llm.getCapabilities());
        }
        
        logger.info("ReflectionInference initialized - maxIterations: {}, scoreThreshold: {}, debug: {}", 
                   this.maxIterations, this.scoreThreshold, this.debug);
    }
    
    public ReflectionInference(Llm llm, MCPService mcpService, MCPServers mcpServers, 
                              Map<String, Object> params) {
        this(llm, mcpService, mcpServers,
             (Integer) params.getOrDefault("maxIterations", ReflectionCriteria.DEFAULT_MAX_ITERATIONS),
             (Boolean) params.getOrDefault("debug", false));
    }
    
    public void setConversationMemory(ConversationMemory memory) {
        this.conversationMemory = memory;
    }
    
    @Override
    public String buildSystemPrompt() {
        return "You are a helpful AI assistant with reflection capabilities. You analyze and improve responses iteratively.";
    }
    
    private List<ToolDefinition> initializeTools() {
        try {
            List<Tool> mcpTools = mcpInfo.listAllTools();
            return llm.convertMcpTools(mcpTools);
            
        } catch (Exception e) {
            logger.error("[REFLECTION] Failed to initialize tools", e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public String processQuery(String query) {
        conversationLogger.info("USER: {}", cleanUserQuery(query));
        
        if (debug) {
            logger.info("[REFLECTION] Processing query with {}: {}", llm.getProviderName(), query);
        }
        
        currentSteps.clear();
        
        try {
            // Gerar resposta inicial
            String currentResponse = generateInitialResponse(query);
            ReflectionStep initialStep = new ReflectionStep(ReflectionStep.StepType.INITIAL_RESPONSE, currentResponse, 0);
            currentSteps.add(initialStep);
            
            conversationLogger.info("INITIAL_RESPONSE: {}", cleanResponse(currentResponse));
            
            // Ciclo de reflexão
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                if (debug) {
                    logger.info("[REFLECTION] Iteration {}/{}", iteration, maxIterations);
                }
                
                // Avaliar resposta atual
                ReflectionResult evaluation = evaluateResponse(query, currentResponse);
                ReflectionStep evaluationStep = new ReflectionStep(ReflectionStep.StepType.EVALUATION, 
                                                                  evaluation.getFeedback(), iteration);
                currentSteps.add(evaluationStep);
                
                conversationLogger.info("EVALUATION_{}: score={}, feedback={}", 
                                       iteration, evaluation.getScore(), cleanFeedback(evaluation.getFeedback()));
                
                if (debug) {
                    logger.info("[REFLECTION] Evaluation score: {}, threshold: {}", 
                               evaluation.getScore(), scoreThreshold);
                }
                
                // Verificar se a qualidade é satisfatória
                if (evaluation.getScore() >= scoreThreshold) {
                    conversationLogger.info("ASSISTANT: {}", cleanResponse(currentResponse));
                    if (debug) {
                        logger.info("[REFLECTION] Score threshold met, returning response");
                    }
                    return currentResponse;
                }
                
                // Gerar versão melhorada
                String improvedResponse = improveResponse(query, currentResponse, evaluation.getFeedback());
                ReflectionStep improvementStep = new ReflectionStep(ReflectionStep.StepType.IMPROVEMENT, 
                                                                  improvedResponse, iteration);
                currentSteps.add(improvementStep);
                
                conversationLogger.info("IMPROVEMENT_{}: {}", iteration, cleanResponse(improvedResponse));
                
                currentResponse = improvedResponse;
            }
            
            // Retornar a melhor resposta encontrada
            conversationLogger.info("ASSISTANT: {}", cleanResponse(currentResponse));
            if (debug) {
                logger.info("[REFLECTION] Max iterations reached, returning final response");
            }
            return currentResponse;
            
        } catch (Exception e) {
            String errorMsg = "Reflection process failed: " + e.getMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            logger.error("[REFLECTION] Error processing query", e);
            return errorMsg;
        }
    }
    
    private String generateInitialResponse(String query) {
        try {
            String prompt = buildInitialPrompt(query);
            
            LlmResponse response = llm.generateWithTools(prompt, availableTools);
            
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to generate initial response: " + response.getErrorMessage());
            }
            
            // Se houver tool calls, executar e incorporar resultados
            if (response.hasToolCalls()) {
                return executeToolsAndGenerateResponse(query, response);
            } else {
                return response.getContent();
            }
            
        } catch (Exception e) {
            logger.error("[REFLECTION] Failed to generate initial response", e);
            return "Error generating initial response: " + e.getMessage();
        }
    }
    
    private String executeToolsAndGenerateResponse(String originalQuery, LlmResponse toolResponse) {
        StringBuilder resultBuilder = new StringBuilder();
        
        for (var toolCall : toolResponse.getToolCalls()) {
            try {
                ToolOperationResult result = toolManager.validateAndExecute(
                    toolCall.getToolName(), toolCall.getArguments());
                
                if (result.isSuccess()) {
                    resultBuilder.append("Tool ").append(toolCall.getToolName())
                                 .append(" result: ").append(result.getResult()).append("\n");
                } else {
                    resultBuilder.append("Tool ").append(toolCall.getToolName())
                                 .append(" failed: ").append(result.getErrorMessage()).append("\n");
                }
            } catch (Exception e) {
                resultBuilder.append("Tool ").append(toolCall.getToolName())
                             .append(" error: ").append(e.getMessage()).append("\n");
            }
        }
        
        // Gerar resposta final baseada nos resultados das ferramentas
        String finalPrompt = String.format(
            "User query: %s\n\nTool results:\n%s\n\nProvide a comprehensive response based on the tool results:",
            originalQuery, resultBuilder.toString());
            
        try {
            LlmResponse finalResponse = llm.generateResponse(finalPrompt);
            return finalResponse.isSuccess() ? finalResponse.getContent() : 
                   "Error generating final response: " + finalResponse.getErrorMessage();
        } catch (Exception e) {
            return "Error generating final response: " + e.getMessage();
        }
    }
    
    private String buildInitialPrompt(String query) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Please provide a helpful and accurate response to the following query:\n\n");
        prompt.append("Query: ").append(query).append("\n\n");
        
        if (!availableTools.isEmpty()) {
            prompt.append("You have access to the following tools if needed:\n");
            for (ToolDefinition tool : availableTools) {
                prompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("Response:");
        
        return prompt.toString();
    }
    
    private ReflectionResult evaluateResponse(String originalQuery, String response) {
        try {
            String evaluationPrompt = buildEvaluationPrompt(originalQuery, response);
            
            LlmResponse llmResponse = llm.generateResponse(evaluationPrompt);
            
            if (!llmResponse.isSuccess()) {
                throw new RuntimeException("Failed to evaluate response: " + llmResponse.getErrorMessage());
            }
            
            return parseEvaluationResult(llmResponse.getContent());
            
        } catch (Exception e) {
            logger.error("[REFLECTION] Failed to evaluate response", e);
            return new ReflectionResult(0.5, "Evaluation failed: " + e.getMessage());
        }
    }
    
    private String buildEvaluationPrompt(String originalQuery, String response) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Evaluate the quality of this response to the given query.\n\n");
        prompt.append("Original Query: ").append(originalQuery).append("\n\n");
        prompt.append("Response to Evaluate: ").append(response).append("\n\n");
        
        prompt.append("Rate the response on a scale of 0.0 to 1.0 based on:\n");
        prompt.append("- Accuracy and correctness\n");
        prompt.append("- Completeness and thoroughness\n");
        prompt.append("- Clarity and helpfulness\n");
        prompt.append("- Relevance to the query\n\n");
        
        prompt.append("Provide your evaluation in this format:\n");
        prompt.append("SCORE: [0.0-1.0]\n");
        prompt.append("FEEDBACK: [detailed feedback on how to improve]\n\n");
        
        prompt.append("Evaluation:");
        
        return prompt.toString();
    }
    
    private ReflectionResult parseEvaluationResult(String evaluationText) {
        try {
            double score = 0.5; // default
            String feedback = "No feedback provided";
            
            String[] lines = evaluationText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("SCORE:")) {
                    String scoreStr = line.substring(6).trim();
                    score = Double.parseDouble(scoreStr);
                } else if (line.startsWith("FEEDBACK:")) {
                    feedback = line.substring(9).trim();
                }
            }
            
            return new ReflectionResult(score, feedback);
            
        } catch (Exception e) {
            logger.error("[REFLECTION] Failed to parse evaluation result: {}", evaluationText, e);
            return new ReflectionResult(0.5, "Failed to parse evaluation: " + e.getMessage());
        }
    }
    
    private String improveResponse(String originalQuery, String previousResponse, String feedback) {
        try {
            String improvementPrompt = buildImprovementPrompt(originalQuery, previousResponse, feedback);
            
            LlmResponse response = llm.generateWithTools(improvementPrompt, availableTools);
            
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to improve response: " + response.getErrorMessage());
            }
            
            // Se houver tool calls, executar e incorporar resultados
            if (response.hasToolCalls()) {
                return executeToolsAndGenerateResponse(originalQuery, response);
            } else {
                return response.getContent();
            }
            
        } catch (Exception e) {
            logger.error("[REFLECTION] Failed to improve response", e);
            return previousResponse; // Fallback para resposta anterior
        }
    }
    
    private String buildImprovementPrompt(String originalQuery, String previousResponse, String feedback) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Improve the following response based on the feedback provided.\n\n");
        prompt.append("Original Query: ").append(originalQuery).append("\n\n");
        prompt.append("Previous Response: ").append(previousResponse).append("\n\n");
        prompt.append("Feedback for Improvement: ").append(feedback).append("\n\n");
        
        if (!availableTools.isEmpty()) {
            prompt.append("You have access to the following tools if needed:\n");
            for (ToolDefinition tool : availableTools) {
                prompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("Provide an improved response that addresses the feedback:\n\n");
        prompt.append("Improved Response:");
        
        return prompt.toString();
    }
    
    @Override
    public void close() {
        currentSteps.clear();
    }
    
    // Helper methods para logging
    
    private String cleanUserQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "[EMPTY_QUERY]";
        }
        return query.length() > 200 ? query.substring(0, 200) + "..." : query;
    }
    
    private String cleanResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "[EMPTY_RESPONSE]";
        }
        return response.length() > 500 ? response.substring(0, 500) + "..." : response;
    }
    
    private String cleanFeedback(String feedback) {
        if (feedback == null || feedback.trim().isEmpty()) {
            return "[EMPTY_FEEDBACK]";
        }
        return feedback.length() > 300 ? feedback.substring(0, 300) + "..." : feedback;
    }
}
