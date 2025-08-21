package com.gazapps.inference.simple;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmResponse;
import com.gazapps.mcp.ToolManager;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * ToolMatcher híbrido que combina QueryIntent (30+ categorias) com LLM otimizado.
 * Oferece alta precisão com baixo consumo de tokens.
 */
public class LLMToolMatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMToolMatcher.class);
    
    private final Llm llmService;
    private final ToolManager toolManager;
    private final boolean debug;
    
    // Cache para otimização de prompts
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();
    private final Map<String, List<Tool>> toolCache = new ConcurrentHashMap<>();
    
    public LLMToolMatcher(Llm llmService, ToolManager toolManager, boolean debug) {
        this.llmService = llmService;
        this.toolManager = toolManager;
        this.debug = debug;
    }
    
    /**
     * Encontra ferramentas relevantes usando abordagem híbrida otimizada.
     */
    public List<MatchResult> findRelevantTools(String query) {
        if (debug) {
            logger.debug("[LLM-MATCHER] Processing query: {}", query);
        }
        
        try {
            // 1. Detectar intenções da query (0 tokens)
            List<QueryIntent> intents = QueryIntent.detectMultipleIntents(query);
            
            if (debug) {
                logger.debug("[LLM-MATCHER] Detected intents: {}", 
                    intents.stream().map(Enum::name).collect(Collectors.joining(", ")));
            }
            
            // 2. Se é conhecimento geral, retorna vazio (resposta direta)
            if (intents.size() == 1 && intents.get(0) == QueryIntent.GENERAL_KNOWLEDGE) {
                if (debug) {
                    logger.debug("[LLM-MATCHER] General knowledge query - no tools needed");
                }
                return Collections.emptyList();
            }
            
            // 3. Filtrar tools relevantes por domínio (0 tokens)
            List<Tool> relevantTools = filterToolsByIntents(intents);
            
            if (relevantTools.isEmpty()) {
                if (debug) {
                    logger.debug("[LLM-MATCHER] No relevant tools found for intents");
                }
                return Collections.emptyList();
            }
            
            // 4. Usar LLM com prompt otimizado (200-400 tokens)
            String optimizedPrompt = buildOptimizedPrompt(query, intents, relevantTools);
            LlmResponse response = llmService.generateResponse(optimizedPrompt);
            
            if (!response.isSuccess()) {
                logger.warn("[LLM-MATCHER] LLM call failed: {}", response.getErrorMessage());
                return Collections.emptyList();
            }
            
            // 5. Parsear resposta e criar MatchResults
            List<MatchResult> results = parseToolSelectionResponse(response.getContent(), relevantTools);
            
            if (debug) {
                logger.debug("[LLM-MATCHER] Found {} tool matches", results.size());
                results.forEach(result -> 
                    logger.debug("[LLM-MATCHER]   - {} (confidence: {:.2f})", 
                        result.getTool().name(), result.getConfidence()));
            }
            
            return results;
            
        } catch (Exception e) {
            logger.error("[LLM-MATCHER] Error in tool matching", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Filtra tools baseado nas intenções detectadas.
     */
    private List<Tool> filterToolsByIntents(List<QueryIntent> intents) {
        String cacheKey = intents.stream().map(Enum::name).collect(Collectors.joining(","));
        
        return toolCache.computeIfAbsent(cacheKey, k -> {
            List<Tool> allTools = toolManager.getAvailableTools();
            Set<String> targetDomains = intents.stream()
                .map(QueryIntent::getDomain)
                .collect(Collectors.toSet());
            
            return allTools.stream()
                .filter(tool -> isToolRelevantForDomains(tool, targetDomains))
                .collect(Collectors.toList());
        });
    }
    
    /**
     * Verifica se uma tool é relevante para os domínios de intenção.
     */
    private boolean isToolRelevantForDomains(Tool tool, Set<String> domains) {
        String toolName = tool.name().toLowerCase();
        String toolDesc = tool.description().toLowerCase();
        
        for (String domain : domains) {
            switch (domain) {
                case "external_data":
                    if (toolName.contains("forecast") || toolName.contains("weather") || 
                        toolName.contains("price") || toolName.contains("news") ||
                        toolDesc.contains("weather") || toolDesc.contains("forecast")) {
                        return true;
                    }
                    break;
                    
                case "temporal":
                    if (toolName.contains("time") || toolName.contains("date") || 
                        toolName.contains("calendar") || toolName.contains("schedule") ||
                        toolDesc.contains("time") || toolDesc.contains("date")) {
                        return true;
                    }
                    break;
                    
                case "filesystem":
                    if (toolName.contains("file") || toolName.contains("directory") || 
                        toolName.contains("read") || toolName.contains("write") ||
                        toolName.contains("list") || toolName.contains("create") ||
                        toolDesc.contains("file") || toolDesc.contains("directory")) {
                        return true;
                    }
                    break;
                    
                case "search":
                    if (toolName.contains("search") || toolName.contains("find") || 
                        toolName.contains("query") || toolName.contains("lookup") ||
                        toolDesc.contains("search") || toolDesc.contains("find")) {
                        return true;
                    }
                    break;
                    
                case "communication":
                    if (toolName.contains("email") || toolName.contains("send") || 
                        toolName.contains("message") || toolName.contains("notify") ||
                        toolDesc.contains("email") || toolDesc.contains("message")) {
                        return true;
                    }
                    break;
                    
                case "calculation":
                    if (toolName.contains("calc") || toolName.contains("convert") || 
                        toolName.contains("math") || toolDesc.contains("calculate")) {
                        return true;
                    }
                    break;
                    
                case "content_creation":
                    if (toolName.contains("create") || toolName.contains("generate") || 
                        toolName.contains("edit") || toolName.contains("image") ||
                        toolDesc.contains("create") || toolDesc.contains("generate")) {
                        return true;
                    }
                    break;
                    
                case "development":
                    if (toolName.contains("git") || toolName.contains("deploy") || 
                        toolName.contains("test") || toolName.contains("build") ||
                        toolDesc.contains("code") || toolDesc.contains("development")) {
                        return true;
                    }
                    break;
                    
                case "iot":
                    if (toolName.contains("smart") || toolName.contains("home") || 
                        toolName.contains("light") || toolName.contains("climate") ||
                        toolDesc.contains("smart") || toolDesc.contains("device")) {
                        return true;
                    }
                    break;
                    
                case "health":
                    if (toolName.contains("health") || toolName.contains("fitness") || 
                        toolName.contains("medical") || toolDesc.contains("health")) {
                        return true;
                    }
                    break;
            }
        }
        
        return false;
    }
    
    /**
     * Constrói prompt otimizado baseado nas intenções e tools relevantes.
     */
    private String buildOptimizedPrompt(String query, List<QueryIntent> intents, List<Tool> relevantTools) {
        String cacheKey = String.format("%s|%s|%d", 
            query.hashCode(), 
            intents.stream().map(Enum::name).collect(Collectors.joining(",")),
            relevantTools.size());
            
        return promptCache.computeIfAbsent(cacheKey, k -> {
            StringBuilder prompt = new StringBuilder();
            
            // Cabeçalho conciso
            prompt.append("Analyze query and select relevant tools:\n\n");
            prompt.append("QUERY: ").append(query).append("\n\n");
            
            // Tools relevantes (apenas as necessárias)
            prompt.append("AVAILABLE TOOLS:\n");
            for (Tool tool : relevantTools) {
                prompt.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
            }
            prompt.append("\n");
            
            // Exemplos contextuais baseados nas intenções
            prompt.append(generateContextualExamples(intents, relevantTools));
            
            // Instruções de formato
            prompt.append("\nRESPONSE FORMAT:\n");
            prompt.append("- List tool names (one per line) if needed\n");
            prompt.append("- Write \"NONE\" if no tools needed\n");
            prompt.append("- For multi-step: list tools in execution order\n");
            
            return prompt.toString();
        });
    }
    
    /**
     * Gera exemplos contextuais baseados nas intenções detectadas.
     */
    private String generateContextualExamples(List<QueryIntent> intents, List<Tool> tools) {
        StringBuilder examples = new StringBuilder("EXAMPLES:\n");
        
        Set<String> domains = intents.stream().map(QueryIntent::getDomain).collect(Collectors.toSet());
        
        for (String domain : domains) {
            switch (domain) {
                case "external_data":
                    tools.stream()
                        .filter(t -> t.name().contains("forecast") || t.name().contains("weather"))
                        .findFirst()
                        .ifPresent(t -> examples.append("✅ \"temperature NYC\" → ").append(t.name()).append("\n"));
                    break;
                    
                case "temporal":
                    tools.stream()
                        .filter(t -> t.name().contains("time") || t.name().contains("date"))
                        .findFirst()
                        .ifPresent(t -> examples.append("✅ \"what time is it\" → ").append(t.name()).append("\n"));
                    break;
                    
                case "filesystem":
                    tools.stream()
                        .filter(t -> t.name().contains("list") || t.name().contains("directory"))
                        .findFirst()
                        .ifPresent(t -> examples.append("✅ \"list my files\" → ").append(t.name()).append("\n"));
                    break;
                    
                case "knowledge":
                    examples.append("❌ \"capital of France\" → NONE\n");
                    break;
            }
        }
        
        // Exemplo multi-step se há múltiplas intenções
        if (intents.size() > 1) {
            examples.append("✅ Multi-step queries → list multiple tools\n");
        }
        
        return examples.toString();
    }
    
    /**
     * Parseia a resposta do LLM e cria MatchResults.
     */
    private List<MatchResult> parseToolSelectionResponse(String response, List<Tool> relevantTools) {
        if (response == null || response.trim().isEmpty() || response.trim().equalsIgnoreCase("NONE")) {
            return Collections.emptyList();
        }
        
        List<MatchResult> results = new ArrayList<>();
        Map<String, Tool> toolMap = relevantTools.stream()
            .collect(Collectors.toMap(Tool::name, tool -> tool));
        
        String[] lines = response.split("\n");
        double baseConfidence = 0.9; // Alta confiança para seleção LLM
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("✅") || line.startsWith("❌") || 
                line.toLowerCase().contains("none")) {
                continue;
            }
            
            // Buscar tool por nome exato ou similar
            Tool matchedTool = findToolByName(line, toolMap);
            if (matchedTool != null) {
                // Confiança decresce levemente para tools adicionais
                double confidence = baseConfidence - (i * 0.1);
                results.add(new MatchResult(matchedTool, Math.max(confidence, 0.5)));
            }
        }
        
        return results;
    }
    
    /**
     * Encontra tool por nome exato ou aproximado.
     */
    private Tool findToolByName(String name, Map<String, Tool> toolMap) {
        // Busca exata
        Tool exact = toolMap.get(name);
        if (exact != null) return exact;
        
        // Busca por substring (para nomes com namespace)
        for (Map.Entry<String, Tool> entry : toolMap.entrySet()) {
            if (entry.getKey().contains(name) || name.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Limpa cache para otimização de memória.
     */
    public void clearCache() {
        promptCache.clear();
        toolCache.clear();
        if (debug) {
            logger.debug("[LLM-MATCHER] Cache cleared");
        }
    }
}
