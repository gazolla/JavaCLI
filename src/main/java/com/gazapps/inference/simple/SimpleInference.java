package com.gazapps.inference.simple;

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
import com.gazapps.llm.tool.ToolCall;
import com.gazapps.llm.tool.ToolDefinition;
import com.gazapps.mcp.MCPInfo;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;
import com.gazapps.mcp.ToolManager;
import com.gazapps.mcp.ToolManager.ToolOperationResult;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * SimpleInference com ToolMatcher híbrido: QueryIntent (35+ categorias) + LLM otimizado.
 * Oferece alta precisão com baixo consumo de tokens através de prompts contextuais.
 */
public class SimpleInference implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(SimpleInference.class);
    private static final Logger conversationLogger = Config.getInferenceConversationLogger("simple");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Llm llmService;
    private final ToolManager toolManager;
    private final LLMToolMatcher llmToolMatcher;
    private final boolean debug;

    /**
     * Constructs SimpleInference com nova arquitetura híbrida.
     */
    public SimpleInference(Llm llmService, MCPService mcpService, MCPServers mcpServers, Map<String, Object> options) {
        this.llmService = Objects.requireNonNull(llmService, "LLM service is required");
        
        MCPInfo mcpInfo = new MCPInfo(
            Objects.requireNonNull(mcpServers, "MCP servers is required"), 
            Objects.requireNonNull(mcpService, "MCP service is required")
        );
        
        this.toolManager = new ToolManager(mcpInfo, mcpServers);
        this.debug = Boolean.parseBoolean(options.getOrDefault("debug", "true").toString());
        this.llmToolMatcher = new LLMToolMatcher(llmService, toolManager, debug);
        
        if (debug) {
            logger.info("[SIMPLE] Initialized HYBRID matcher with LLM provider: {}, capabilities: {}", 
                       llmService.getProviderName(), llmService.getCapabilities());
        }
    }

    @Override
    public ChatEngineBuilder.InferenceStrategy getStrategyName() {
        return ChatEngineBuilder.InferenceStrategy.SIMPLE;
    }

    @Override
    public String processQuery(String query) {
        conversationLogger.info("USER: {}", cleanForLog(query, 200));
        
        if (debug) {
            logger.info("[SIMPLE] Processing query with HYBRID matcher: {}", query);
        }

        try {
            // 1. Usar LLMToolMatcher híbrido para encontrar ferramentas relevantes
            List<MatchResult> matches = llmToolMatcher.findRelevantTools(query);
            
            // 2. Decisão baseada nos resultados do matching
            if (matches.isEmpty()) {
                return handleDirectResponse(query);
            }
            
            if (matches.size() == 1 && matches.get(0).shouldUseTool()) {
                return handleSingleToolExecution(query, matches.get(0));
            }
            
            if (matches.size() > 1) {
                return handleMultiStepQuery(query, matches);
            }
            
            // Fallback: resposta direta se confiança baixa
            return handleDirectResponse(query);

        } catch (Exception e) {
            String errorMsg = "Erro ao processar solicitação: " + e.getMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            logger.error("[SIMPLE] Error processing query", e);
            return errorMsg;
        }
    }
    
    /**
     * Manipula resposta direta do LLM sem ferramentas.
     */
    private String handleDirectResponse(String query) {
        conversationLogger.info("ANALYSIS: Direct response");
        
        String prompt = "Responda diretamente à seguinte pergunta usando seu conhecimento:\n\n" + query;
        
        LlmResponse response = llmService.generateResponse(prompt);
        
        if (!response.isSuccess()) {
            String errorMsg = "Falha ao gerar resposta: " + response.getErrorMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            return errorMsg;
        }
        
        String content = response.getContent();
        conversationLogger.info("ASSISTANT: {}", cleanForLog(content, 500));
        return content;
    }
    
    /**
     * Manipula execução de uma única ferramenta usando approach otimizada.
     */
    private String handleSingleToolExecution(String query, MatchResult match) {
        conversationLogger.info("ANALYSIS: Single tool execution");
        
        // Usar prompt otimizado baseado na ferramenta específica
        String prompt = buildOptimizedSingleToolPrompt(query, match.getTool());
        
        LlmResponse response = llmService.generateResponse(prompt);
        
        if (!response.isSuccess()) {
            String errorMsg = "Falha na análise da ferramenta: " + response.getErrorMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            return errorMsg;
        }
        
        String analysisResult = response.getContent();
        
        if (analysisResult.startsWith("TOOL:")) {
            return executeToolAndRespond(analysisResult, query);
        } else {
            conversationLogger.info("ASSISTANT: {}", cleanForLog(analysisResult, 500));
            return analysisResult;
        }
    }
    
    /**
     * Manipula queries compostas usando generateWithTools.
     */
    private String handleMultiStepQuery(String query, List<MatchResult> matches) {
        conversationLogger.info("ANALYSIS: Multi-step execution ({} candidates)", matches.size());
        
        // Converter matches para ToolDefinitions
        List<ToolDefinition> toolDefinitions = matches.stream()
            .map(match -> convertToolToDefinition(match.getTool()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        String prompt = buildOptimizedMultiStepPrompt(query, matches);
        
        // Usar generateWithTools para permitir function calling
        LlmResponse response = llmService.generateWithTools(prompt, toolDefinitions);
        
        if (!response.isSuccess()) {
            String errorMsg = "Falha na execução multi-step: " + response.getErrorMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            return errorMsg;
        }
        
        // Se LLM retornou tool calls, executar sequência
        if (response.hasToolCalls()) {
            return executeToolSequence(response.getToolCalls(), query);
        }
        
        // Senão, retornar resposta direta
        String content = response.getContent();
        conversationLogger.info("ASSISTANT: {}", cleanForLog(content, 500));
        return content;
    }
    
    /**
     * Constrói prompt otimizado para execução de ferramenta única.
     */
    private String buildOptimizedSingleToolPrompt(String query, Tool tool) {
        QueryIntent intent = QueryIntent.detectIntent(query);
        
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Execute a seguinte solicitação usando a ferramenta mais apropriada:\n\n");
        prompt.append("SOLICITAÇÃO: ").append(query).append("\n\n");
        
        prompt.append("FERRAMENTA RECOMENDADA:\n");
        prompt.append("- ").append(tool.name()).append(": ").append(tool.description());
        
        // Adicionar exemplos específicos baseados na intenção
        prompt.append("\n\n").append(generateIntentSpecificExamples(intent, tool));
        
        prompt.append("\nINSTRUÇÕES:\n");
        prompt.append("- Se a ferramenta for relevante, responda: TOOL:").append(tool.name()).append(":{\"param\":\"value\"}\n");
        prompt.append("- Se não for relevante, responda diretamente\n");
        prompt.append("- Use TODOS seus conhecimentos (História, cultura, idiomas, "
        		+ "Ciência, matemática, física, Business, economia, política, Arte, "
        		+ "literatura, música, Culinária, tradições locais, Tecnologia, "
        		+ "protocolos, APIs) para fornecer parâmetros corretos.");
       
        
        return prompt.toString();
    }
    
    /**
     * Constrói prompt otimizado para execução multi-step.
     */
    private String buildOptimizedMultiStepPrompt(String query, List<MatchResult> matches) {
        List<QueryIntent> intents = QueryIntent.detectMultipleIntents(query);
        
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Execute a seguinte solicitação usando as ferramentas na ordem correta:\n\n");
        prompt.append("SOLICITAÇÃO: ").append(query).append("\n\n");
        
        prompt.append("FERRAMENTAS RECOMENDADAS:\n");
        for (MatchResult match : matches) {
            Tool tool = match.getTool();
            prompt.append("- ").append(tool.name()).append(": ").append(tool.description())
                  .append(" (confiança: ").append(String.format("%.1f", match.getConfidence() * 100)).append("%)");
            prompt.append("\n");
        }
        
        // Adicionar exemplos multi-step baseados nas intenções
        prompt.append("\n").append(generateMultiStepExamples(intents, matches));
        
        prompt.append("\nINSTRUÇÕES:\n");
        prompt.append("- Execute as ferramentas na ordem necessária\n");
        prompt.append("- Use resultados de uma ferramenta como entrada da próxima quando aplicável\n");
        prompt.append("- Use TODOS seus conhecimentos (História, cultura, idiomas, "
        		+ "Ciência, matemática, física, Business, economia, política, Arte, "
        		+ "literatura, música, Culinária, tradições locais, Tecnologia, "
        		+ "protocolos, APIs) para fornecer parâmetros corretos.");
        
        return prompt.toString();
    }
    
    /**
     * Gera exemplos específicos baseados na intenção detectada.
     */
    private String generateIntentSpecificExamples(QueryIntent intent, Tool tool) {
        return switch (intent) {
            case WEATHER -> String.format(
                "EXEMPLO:\n✅ \"temperatura em NYC\" → TOOL:%s:{\"latitude\":40.7128,\"longitude\":-74.0060}",
                tool.name());
                
            case DATETIME -> String.format(
                "EXEMPLO:\n✅ \"que horas são\" → TOOL:%s:{\"timezone\":\"America/Sao_Paulo\"}",
                tool.name());
                
            case FILE_LIST -> String.format(
                "EXEMPLO:\n✅ \"listar arquivos\" → TOOL:%s:{\"path\":\"documents\"}",
                tool.name());
                
            default -> String.format(
                "EXEMPLO:\n✅ Queries que precisam de dados externos → TOOL:%s",
                tool.name());
        };
    }
    
    /**
     * Gera exemplos para queries multi-step.
     */
    private String generateMultiStepExamples(List<QueryIntent> intents, List<MatchResult> matches) {
        StringBuilder examples = new StringBuilder("EXEMPLOS MULTI-STEP:\n");
        
        if (intents.contains(QueryIntent.WEATHER) && intents.stream().anyMatch(i -> i.getDomain().equals("filesystem"))) {
            examples.append("✅ \"clima em NYC e salvar arquivo\" → get-forecast + write_file\n");
        }
        
        if (intents.contains(QueryIntent.DATETIME) && intents.stream().anyMatch(i -> i.getDomain().equals("filesystem"))) {
            examples.append("✅ \"que horas são e criar relatório\" → get_current_time + write_file\n");
        }
        
        examples.append("✅ Execute ferramentas em sequência lógica\n");
        
        return examples.toString();
    }
    
    /**
     * Executa uma única ferramenta baseada na resposta do LLM.
     */
    private String executeToolAndRespond(String toolResponse, String originalQuery) {
        try {
            // Parse TOOL:name:{json} format
            String[] parts = toolResponse.substring(5).split(":", 2);
            String toolName = parts[0];
            String argsJson = parts.length > 1 ? parts[1] : "{}";
            
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            
            conversationLogger.info("TOOL_CALL: {}({})", toolName, formatArgs(args));
            
            ToolOperationResult result = toolManager.validateAndExecute(toolName, args);
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                    errorMsg += ". Sugestões: " + String.join(", ", result.getSuggestions());
                }
                conversationLogger.info("ERROR: {}", errorMsg);
                return errorMsg;
            }
            
            String toolResult = result.getResult();
            conversationLogger.info("TOOL_RESULT: {}", cleanForLog(toolResult, 400));
            
            // Gerar resposta contextualizada
            String finalPrompt = String.format(
                "Baseado na execução da ferramenta:\n\nFerramenta: %s\nResultado: %s\n\nSolicitação original: %s\n\nForneça uma resposta abrangente incorporando o resultado da ferramenta.",
                toolName, toolResult, originalQuery
            );
                
            LlmResponse finalResponse = llmService.generateResponse(finalPrompt);
            
            if (!finalResponse.isSuccess()) {
                return "Ferramenta executada com sucesso, mas falha ao gerar resposta final: " + finalResponse.getErrorMessage();
            }
            
            String responseContent = finalResponse.getContent();
            conversationLogger.info("ASSISTANT: {}", cleanForLog(responseContent, 500));
            
            return responseContent;
            
        } catch (Exception e) {
            String errorMsg = "Falha na execução da ferramenta: " + e.getMessage();
            conversationLogger.info("ERROR: {}", errorMsg);
            return errorMsg;
        }
    }
    
    /**
     * Executa sequência de ferramentas para queries compostas.
     */
    private String executeToolSequence(List<ToolCall> toolCalls, String originalQuery) {
        StringBuilder results = new StringBuilder();
        Map<String, String> context = new java.util.HashMap<>();
        
        conversationLogger.info("SEQUENCE: Executing {} tools", toolCalls.size());
        
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            
            try {
                conversationLogger.info("TOOL_CALL[{}]: {}({})", i + 1, toolCall.getToolName(), formatArgs(toolCall.getArguments()));
                
                ToolOperationResult result = toolManager.validateAndExecute(toolCall.getToolName(), toolCall.getArguments());
                
                if (!result.isSuccess()) {
                    String errorMsg = String.format("Falha no passo %d: %s", i + 1, result.getErrorMessage());
                    conversationLogger.info("ERROR: {}", errorMsg);
                    return errorMsg;
                }
                
                String stepResult = result.getResult();
                context.put(toolCall.getToolName() + "_result", stepResult);
                results.append(String.format("Passo %d (%s): %s\n", i + 1, toolCall.getToolName(), stepResult));
                
                conversationLogger.info("TOOL_RESULT[{}]: {}", i + 1, cleanForLog(stepResult, 200));
                
            } catch (Exception e) {
                String errorMsg = String.format("Erro no passo %d: %s", i + 1, e.getMessage());
                conversationLogger.info("ERROR: {}", errorMsg);
                return errorMsg;
            }
        }
        
        // Consolidar resultados
        String finalPrompt = String.format(
            "Consolide os seguintes resultados em uma resposta final:\n\nSolicitação original: %s\n\nResultados:\n%s\n\nForneça um resumo consolidado e útil.",
            originalQuery, results.toString()
        );
        
        LlmResponse finalResponse = llmService.generateResponse(finalPrompt);
        
        if (!finalResponse.isSuccess()) {
            return "Sequência executada com sucesso:\n" + results.toString();
        }
        
        String responseContent = finalResponse.getContent();
        conversationLogger.info("ASSISTANT: {}", cleanForLog(responseContent, 500));
        
        return responseContent;
    }
    
    /**
     * Converte Tool MCP para ToolDefinition.
     */
    private ToolDefinition convertToolToDefinition(Tool tool) {
        // Delega para o LLM service que já tem essa lógica
        List<Tool> singleToolList = List.of(tool);
        List<ToolDefinition> definitions = llmService.convertMcpTools(singleToolList);
        return definitions.isEmpty() ? null : definitions.get(0);
    }

    @Override
    public String buildSystemPrompt() {
        return "Sistema de inferência híbrido com 35+ categorias de QueryIntent e LLM otimizado para seleção contextual de ferramentas.";
    }

    @Override
    public void close() {
        // Limpar cache do LLMToolMatcher
        if (llmToolMatcher != null) {
            llmToolMatcher.clearCache();
        }
    }
    
    // Helper methods para logging
    
    private String cleanForLog(String text, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            return "[EMPTY]";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
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
