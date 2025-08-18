package com.gazapps.inference.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.core.ConversationMemory;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.mcp.MCPInfo;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Implementação do padrão Reflection para auto-avaliação e melhoria iterativa de respostas.
 * 
 * O processo funciona em ciclos:
 * 1. Gera resposta inicial
 * 2. Avalia qualidade da resposta
 * 3. Se score < threshold, gera versão melhorada
 * 4. Repete até score satisfatório ou max iterações
 */
public class ReflectionInference implements Inference, AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ReflectionInference.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Llm llm;
    private final MCPService mcpService;
    private final MCPServers mcpServers;
    private final int maxIterations;
    private final double scoreThreshold;
    private final boolean debug;
    
    private ConversationMemory conversationMemory;
    private final List<ReflectionStep> currentSteps;
    private final MCPInfo mcpInfo;
    private List<FunctionDeclaration> availableTools;
    
    @Override
    public String getStrategyName() {
        return "reflection";
    }

    
    public ReflectionInference(Llm llm, MCPService mcpService, MCPServers mcpServers, 
                              int maxIterations, boolean debug) {
        this.llm = llm;
        this.mcpService = mcpService;
        this.mcpServers = mcpServers;
        this.maxIterations = maxIterations > 0 ? maxIterations : ReflectionCriteria.DEFAULT_MAX_ITERATIONS;
        this.scoreThreshold = ReflectionCriteria.DEFAULT_SCORE_THRESHOLD;
        this.debug = debug;
        this.currentSteps = new ArrayList<>();
        this.mcpInfo = new MCPInfo(mcpServers, mcpService);
        this.availableTools = initializeTools();
        
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
    
    private List<FunctionDeclaration> initializeTools() {
        try {
            List<FunctionDeclaration> tools = new ArrayList<>();
            List<Tool> mcpTools = mcpInfo.listAllTools();
            
            for (Tool tool : mcpTools) {
                String toolName = tool.name();
                FunctionDeclaration funcDecl = llm.convertMcpToolToFunction(tool, toolName);
                tools.add(funcDecl);
            }
            
            return tools;
        } catch (Exception e) {
            logger.warn("Failed to initialize tools: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public String processQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be null or empty");
        }
        
        long startTime = System.currentTimeMillis();
        currentSteps.clear();
        
        try {
            logger.info("Starting reflection cycle for query: {}", query.substring(0, Math.min(50, query.length())));
            
            // 1. Preparar contexto de ferramentas
            String toolContext = buildToolContext();
            
            // 2. Gerar resposta inicial
            String currentResponse = generateInitialResponse(query, toolContext);
            currentSteps.add(new ReflectionStep(ReflectionStep.StepType.INITIAL_RESPONSE, currentResponse, 0));
            
            if (debug) {
                logger.debug("Initial response generated (length: {})", currentResponse.length());
            }
            
            // 3. Ciclo de reflexão
            int iteration = 1;
            while (iteration <= maxIterations) {
                
                // Avaliar resposta atual
                ReflectionResult evaluation = evaluateResponse(query, currentResponse, toolContext);
                currentSteps.add(new ReflectionStep(ReflectionStep.StepType.EVALUATION, "", evaluation, iteration));
                
                if (debug) {
                    logger.debug("Iteration {}: Evaluation complete - score: {:.2f}, needs_improvement: {}", 
                               iteration, evaluation.getOverallScore(), evaluation.needsImprovement());
                }
                
                // Verificar se deve continuar
                if (!shouldContinueIteration(evaluation, iteration)) {
                    if (debug) {
                        logger.debug("Reflection complete - score: {:.2f} meets threshold: {:.2f}", 
                                   evaluation.getOverallScore(), scoreThreshold);
                    }
                    break;
                }
                
                // Gerar versão melhorada
                String improvedResponse = improveResponse(query, currentResponse, evaluation, toolContext);
                currentSteps.add(new ReflectionStep(ReflectionStep.StepType.IMPROVEMENT, improvedResponse, iteration));
                
                currentResponse = improvedResponse;
                iteration++;
                
                if (debug) {
                    logger.debug("Iteration {}: Improvement generated (length: {})", iteration - 1, improvedResponse.length());
                }
            }
            
            // Resposta final
            currentSteps.add(new ReflectionStep(ReflectionStep.StepType.FINAL, currentResponse, iteration));
            
            long endTime = System.currentTimeMillis();
            logger.info("Reflection completed: {} iterations, {} steps, {:.2f}s", 
                       iteration - 1, currentSteps.size(), (endTime - startTime) / 1000.0);
            
            return currentResponse;
            
        } catch (ReflectionException e) {
            logger.error("Reflection failed: {}", e.toString());
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error in reflection process: {}", e.getMessage(), e);
            throw new ReflectionException("Unexpected error in reflection process: " + e.getMessage(), "process", -1, query);
        }
    }
    
    /**
     * Gera resposta inicial usando contexto de ferramentas
     */
    private String generateInitialResponse(String query, String toolContext) {
        try {
            String prompt = ReflectionCriteria.buildInitialPrompt(query, toolContext);
            String response = llm.generateResponse(prompt, availableTools);
            
            if (response == null || response.trim().isEmpty()) {
                throw ReflectionException.evaluationFailed("Empty response from LLM", 0);
            }
            
            // KISS: Copy function calling logic from SimpleSequential
            return processResponseWithTools(response, query);
            
        } catch (Exception e) {
            throw new ReflectionException("Failed to generate initial response: " + e.getMessage(), "initial", 0, query);
        }
    }
    
    /**
     * Avalia qualidade da resposta atual
     */
    private ReflectionResult evaluateResponse(String query, String response, String toolContext) {
        try {
            String evaluationPrompt = ReflectionCriteria.buildEvaluationPrompt(query, response, toolContext);
            String evaluationResponse = llm.generateResponse(evaluationPrompt, new ArrayList<>());
            
            if (evaluationResponse == null || evaluationResponse.trim().isEmpty()) {
                throw ReflectionException.evaluationFailed("Empty evaluation response from LLM", -1);
            }
            
            return ReflectionResult.parse(evaluationResponse);
            
        } catch (ReflectionException e) {
            throw e; // Re-throw reflection exceptions
        } catch (Exception e) {
            throw ReflectionException.evaluationFailed("Error during evaluation: " + e.getMessage(), -1);
        }
    }
    
    /**
     * Gera versão melhorada da resposta baseada na avaliação
     */
    private String improveResponse(String query, String response, ReflectionResult evaluation, String toolContext) {
        try {
            String improvementPrompt = ReflectionCriteria.buildImprovementPrompt(query, response, evaluation, toolContext);
            String improvedResponse = llm.generateResponse(improvementPrompt, availableTools);
            
            if (improvedResponse == null || improvedResponse.trim().isEmpty()) {
                throw ReflectionException.improvementFailed("Empty improvement response from LLM", -1);
            }
            
            // KISS: Also process tools in improvements
            return processResponseWithTools(improvedResponse, query);
            
        } catch (ReflectionException e) {
            throw e; // Re-throw reflection exceptions
        } catch (Exception e) {
            throw ReflectionException.improvementFailed("Error during improvement: " + e.getMessage(), -1);
        }
    }
    
    /**
     * Determina se deve continuar com mais iterações
     */
    private boolean shouldContinueIteration(ReflectionResult evaluation, int currentIteration) {
        // Parar se atingiu max iterações
        if (currentIteration >= maxIterations) {
            return false;
        }
        
        // Parar se score é satisfatório
        if (evaluation.getOverallScore() >= scoreThreshold) {
            return false;
        }
        
        // Continuar se precisa de melhoria
        return evaluation.needsImprovement();
    }
    
    /**
     * Constrói contexto das ferramentas MCP disponíveis
     */
    private String buildToolContext() {
        StringBuilder context = new StringBuilder();
        context.append("Available tools and their capabilities:\n");
        
        try {
            List<Tool> allTools = mcpInfo.listAllTools();
            if (allTools.isEmpty()) {
                context.append("- No external tools currently available\n");
            } else {
                for (Tool tool : allTools) {
                    context.append(String.format("- %s: %s\n", 
                                 tool.name(), 
                                 tool.description()));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to build tool context: {}", e.getMessage());
            context.append("- Error loading tool information\n");
        }
        
        return context.toString();
    }
    
    /**
     * Identifica ferramentas relevantes para a query (para futuras otimizações)
     */
    @SuppressWarnings("unused")
    private List<String> identifyRelevantTools(String query) {
        List<String> relevantTools = new ArrayList<>();
        
        try {
            String queryLower = query.toLowerCase();
            
            // Buscar por palavras-chave específicas
            if (queryLower.contains("file") || queryLower.contains("arquivo")) {
                List<Tool> fileTools = mcpInfo.listToolsByKeyWord("file");
                fileTools.forEach(tool -> relevantTools.add(tool.name()));
            }
            
            if (queryLower.contains("weather") || queryLower.contains("clima")) {
                List<Tool> weatherTools = mcpInfo.listToolsByKeyWord("weather");
                weatherTools.forEach(tool -> relevantTools.add(tool.name()));
            }
            
            if (queryLower.contains("remember") || queryLower.contains("memory") || queryLower.contains("lembrar")) {
                List<Tool> memoryTools = mcpInfo.listToolsByKeyWord("memory");
                memoryTools.forEach(tool -> relevantTools.add(tool.name()));
            }
            
        } catch (Exception e) {
            logger.warn("Failed to identify relevant tools: {}", e.getMessage());
        }
        
        return relevantTools;
    }
    
    /**
     * Retorna steps do processo atual (para debug/análise)
     */
    public List<ReflectionStep> getCurrentSteps() {
        return new ArrayList<>(currentSteps);
    }
    
    /**
     * Retorna configurações atuais
     */
    public Map<String, Object> getConfiguration() {
        return Map.of(
            "maxIterations", maxIterations,
            "scoreThreshold", scoreThreshold,
            "debug", debug,
            "llmProvider", llm.getProviderName()
        );
    }
    
    /**
     * KISS: Copy function calling logic from SimpleSequential
     */
    private String processResponseWithTools(String response, String query) {
        try {
            // Check for function calls in different formats
            if (response.startsWith("FUNCTION_CALL:")) {
                String[] parts = response.split(":", 3);
                if (parts.length >= 3) {
                    String functionName = parts[1];
                    String argsJson = parts[2];
                    Map<String, Object> arguments = objectMapper.readValue(argsJson, Map.class);
                    
                    String toolResult = executeFunction(functionName, arguments);
                    return processToolResult(query, functionName, toolResult);
                }
            }
            
            // Check for JSON array function calls (Groq format)
            if (response.trim().startsWith("[") && response.contains("name") && response.contains("parameters")) {
                try {
                    String cleanedResponse = response.trim();
                    com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cleanedResponse);
                    if (jsonArray.isArray() && jsonArray.size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode firstCall = jsonArray.get(0);
                        if (firstCall.has("name") && firstCall.has("parameters")) {
                            String functionName = firstCall.get("name").asText();
                            com.fasterxml.jackson.databind.JsonNode paramsNode = firstCall.get("parameters");
                            Map<String, Object> arguments = objectMapper.convertValue(paramsNode, Map.class);
                            
                            String toolResult = executeFunction(functionName, arguments);
                            return processToolResult(query, functionName, toolResult);
                        }
                    }
                } catch (Exception jsonEx) {
                    logger.warn("Error parsing JSON function call: " + jsonEx.getMessage());
                }
            }
            
            return response.trim();
            
        } catch (Exception e) {
            logger.error("Error processing response with tools: " + e.getMessage());
            return response.trim();
        }
    }
    
    /**
    * Executa ferramenta MCP usando mapeamento de nome simples para namespace
    */
    private String executeFunction(String functionName, Map<String, Object> args) {
    try {
    // Primeiro, tentar obter o nome com namespace
    String namespacedToolName = mcpServers.getNamespacedToolName(functionName);
    
    if (namespacedToolName == null) {
      // Fallback: talvez já seja um nome com namespace
     namespacedToolName = functionName;
    }
    
     // Usar MCPInfo para execução
      return mcpInfo.executeTool(namespacedToolName, args);
			
		} catch (Exception e) {
			String errorMsg = "Tool execution failed: " + e.getMessage();
			logger.error(errorMsg, e);
			return errorMsg;
		}
	}
    
    /**
     * KISS: Copy from SimpleSequential
     */
    private String processToolResult(String originalQuery, String toolName, String toolResult) {
        try {
            String processingPrompt = String.format("""
                You are processing tool results. Create a natural, conversational response in Portuguese.
                
                ORIGINAL QUESTION: "%s"
                TOOL USED: %s
                TOOL RESULT:
                %s
                
                Create a helpful response based on the tool results.
                """, originalQuery, toolName, toolResult);
            
            String response = llm.generateResponse(processingPrompt, null);
            return response != null && !response.isEmpty() ? response : "Tool executed successfully.";
            
        } catch (Exception e) {
            return "Tool result: " + toolResult;
        }
    }
    
    @Override
    public void close() {
        currentSteps.clear();
        logger.info("ReflectionInference closed");
    }
    
    @Override
    public String toString() {
        return String.format("ReflectionInference{maxIterations=%d, scoreThreshold=%.2f, debug=%s, llm=%s}", 
                           maxIterations, scoreThreshold, debug, llm.getProviderName());
    }
}
