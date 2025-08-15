package com.gazapps.inference.tooluse;

import java.util.*;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic Tool Matcher - 100% Dynamic, Language-Agnostic, KISS Implementation
 * 
 * Substitui regras hardcoded por matching baseado em:
 * - Entidades universais (URLs, files, locations, etc.)
 * - Compatibilidade de parâmetros
 * - Context do servidor
 * - Padrões de nomes
 * - Histórico de sucesso
 */
public class DynamicToolMatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(DynamicToolMatcher.class);
    
    // Estado mínimo para aprendizado
    private final Map<String, Double> successHistory = new HashMap<>();
    
    // Threshold para matching
    private static final double MATCH_THRESHOLD = 0.5;
    
    /**
     * Resultado detalhado do matching
     */
    public static class MatchResult {
        public final boolean matches;
        public final double confidence;
        public final boolean isComplex;
        public final Set<String> detectedEntities;
        
        public MatchResult(boolean matches, double confidence, boolean isComplex, Set<String> entities) {
            this.matches = matches;
            this.confidence = confidence;
            this.isComplex = isComplex;
            this.detectedEntities = entities;
        }
    }
    
    /**
     * Método principal - substitui isQueryMatchingTool (backward compatibility)
     */
    public boolean matches(String query, String toolDescription, String toolName, 
                          String serverName, Map<String, Object> toolSchema) {
        return matchesWithDetails(query, toolDescription, toolName, serverName, toolSchema).matches;
    }
    
    /**
     * Método detalhado - retorna informações completas do matching
     */
    public MatchResult matchesWithDetails(String query, String toolDescription, String toolName, 
                                         String serverName, Map<String, Object> toolSchema) {
        
        Set<String> entities = extractEntities(query);
        boolean isComplex = detectQueryComplexity(query, entities);
        double score = calculateScore(query, toolDescription, toolName, serverName, toolSchema);
        boolean matches = score > MATCH_THRESHOLD;
        
        if (logger.isDebugEnabled()) {
            logger.debug("[DYNAMIC_MATCHER] Query: '{}' | Tool: '{}' | Score: {:.3f} | Complex: {} | Entities: {} | Match: {}", 
                        query, toolName, score, isComplex, entities, matches);
        }
        
        return new MatchResult(matches, score, isComplex, entities);
    }
    
    /**
     * Detecta se uma query é complexa (múltiplas intenções/entidades)
     */
    private boolean detectQueryComplexity(String query, Set<String> entities) {
        String queryLower = query.toLowerCase();
        
        // Contador de intenções/domínios diferentes
        int domainCount = 0;
        
        // Domínio 1: Operações de arquivo
        if (queryLower.matches(".*(?:crie|create|arquivo|file|write|escreva|salve|save).*") ||
            entities.contains("FILE")) {
            domainCount++;
        }
        
        // Domínio 2: Operações de clima/tempo
        if (queryLower.matches(".*(?:tempo|weather|clima|temperature|forecast|previsão).*") ||
            entities.contains("LOCATION")) {
            domainCount++;
        }
        
        // Domínio 3: Operações de feed/notícias
        if (queryLower.matches(".*(?:feed|rss|news|notícias|manchetes).*") ||
            entities.contains("URL")) {
            domainCount++;
        }
        
        // Domínio 4: Operações de tempo/hora
        if (queryLower.matches(".*(?:hora|time|quando|when|agora|now).*") ||
            entities.contains("TIME")) {
            domainCount++;
        }
        
        // Complexidade: múltiplos domínios ou múltiplas entidades
        boolean multiDomain = domainCount > 1;
        boolean multiEntity = entities.size() > 1;
        boolean hasConjunctions = queryLower.matches(".*(?:com|with|e|and|usando|using).*");
        
        boolean isComplex = multiDomain || (multiEntity && hasConjunctions);
        
        if (logger.isDebugEnabled() && isComplex) {
            logger.debug("[DYNAMIC_MATCHER] Complex query detected: domains={}, entities={}, conjunctions={}", 
                        domainCount, entities.size(), hasConjunctions);
        }
        
        return isComplex;
    }
    
    /**
     * Registra resultado para aprendizado automático
     */
    public void recordResult(String toolName, boolean success) {
        double currentRate = successHistory.getOrDefault(toolName, 0.5);
        double adjustment = success ? 0.1 : -0.1;
        double newRate = Math.max(0.0, Math.min(1.0, currentRate + adjustment));
        successHistory.put(toolName, newRate);
        
        if (logger.isDebugEnabled()) {
            logger.debug("[DYNAMIC_MATCHER] Learning: Tool '{}' | Success: {} | Rate: {:.2f} -> {:.2f}", 
                        toolName, success, currentRate, newRate);
        }
    }
    
    /**
     * Calcula score de compatibilidade (0.0 - 1.0)
     */
    private double calculateScore(String query, String toolDescription, String toolName, 
                                 String serverName, Map<String, Object> toolSchema) {
        
        double score = 0.0;
        
        // 1. ENTIDADES UNIVERSAIS (40% do score)
        Set<String> queryEntities = extractEntities(query);
        score += entityParameterMatch(queryEntities, toolSchema) * 0.4;
        
        // 2. CONTEXT HINTS (30% do score)
        score += contextMatch(query, toolDescription, serverName) * 0.3;
        
        // 3. NAME PATTERNS (20% do score)
        score += namePatternMatch(query, toolName) * 0.2;
        
        // 4. SUCCESS HISTORY (10% do score)
        score += getSuccessRate(toolName) * 0.1;
        
        return Math.min(score, 1.0);
    }
    
    /**
     * Extrai entidades universais da query usando regex simples
     */
    private Set<String> extractEntities(String text) {
        Set<String> entities = new HashSet<>();
        String textLower = text.toLowerCase();
        
        // URLs: http/https
        if (textLower.matches(".*https?://\\S+.*")) {
            entities.add("URL");
        }
        
        // Files: extensões comuns
        if (textLower.matches(".*\\w+\\.(txt|json|xml|csv|log|md|yml|yaml|conf|config|ini).*")) {
            entities.add("FILE");
        }
        
        // Locations: palavras capitalizadas (São Paulo, New York)
        Pattern locationPattern = Pattern.compile("\\b[A-ZÀ-Ÿ][a-zà-ÿ]+(?:\\s+[A-ZÀ-Ÿ][a-zà-ÿ]+)*\\b");
        if (locationPattern.matcher(text).find()) {
            entities.add("LOCATION");
        }
        
        // Numbers: inteiros e decimais
        if (textLower.matches(".*\\d+(?:[.,]\\d+)?.*")) {
            entities.add("NUMBER");
        }
        
        // Time: expressões temporais universais
        if (textLower.matches(".*(?:\\d{1,2}[:\\.h]\\d{2}|hoje|agora|now|time|hora|when|quando|date|data).*")) {
            entities.add("TIME");
        }
        
        // Email: padrão básico
        if (textLower.matches(".*\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b.*")) {
            entities.add("EMAIL");
        }
        
        return entities;
    }
    
    /**
     * Verifica compatibilidade entre entidades da query e parâmetros da ferramenta
     */
    private double entityParameterMatch(Set<String> entities, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty() || entities.isEmpty()) {
            return 0.0;
        }
        
        double matches = 0.0;
        double total = entities.size();
        
        Set<String> paramNames = schema.keySet();
        String allParams = String.join(" ", paramNames).toLowerCase();
        
        for (String entity : entities) {
            switch (entity) {
                case "URL" -> {
                    if (allParams.matches(".*(?:url|link|uri|href|endpoint|address).*")) {
                        matches += 1.0;
                    }
                }
                case "FILE" -> {
                    if (allParams.matches(".*(?:file|path|filename|filepath|document|content).*")) {
                        matches += 1.0;
                    }
                }
                case "LOCATION" -> {
                    if (allParams.matches(".*(?:location|latitude|longitude|city|address|place|region|country).*")) {
                        matches += 1.0;
                    }
                }
                case "TIME" -> {
                    if (allParams.matches(".*(?:time|date|timezone|when|timestamp|datetime|schedule).*")) {
                        matches += 1.0;
                    }
                }
                case "NUMBER" -> {
                    if (allParams.matches(".*(?:amount|count|size|limit|number|quantity|value).*")) {
                        matches += 1.0;
                    }
                }
                case "EMAIL" -> {
                    if (allParams.matches(".*(?:email|mail|recipient|sender|contact).*")) {
                        matches += 1.0;
                    }
                }
            }
        }
        
        return matches / total;
    }
    
    /**
     * Matching baseado em context do servidor e descrição da ferramenta
     */
    private double contextMatch(String query, String toolDescription, String serverName) {
        String queryLower = query.toLowerCase();
        String descLower = toolDescription != null ? toolDescription.toLowerCase() : "";
        String serverLower = serverName != null ? serverName.toLowerCase() : "";
        
        double score = 0.0;
        
        // Context hints do servidor (language-agnostic)
        if (serverLower.matches(".*(?:weather|forecast|clima|tempo).*")) {
            if (queryLower.matches(".*(?:tempo|weather|clima|temperature|forecast|previsão).*")) {
                score += 0.8;
            }
        }
        
        if (serverLower.matches(".*(?:file|filesystem|disk|storage).*")) {
            if (queryLower.matches(".*(?:arquivo|file|create|crie|read|leia|write|escreva|list|liste).*")) {
                score += 0.8;
            }
        }
        
        if (serverLower.matches(".*(?:feed|rss|news|noticia).*")) {
            if (queryLower.matches(".*(?:feed|rss|news|notícias|manchetes|headlines).*")) {
                score += 0.8;
            }
        }
        
        if (serverLower.matches(".*(?:time|clock|datetime).*")) {
            if (queryLower.matches(".*(?:hora|time|quando|when|agora|now).*")) {
                score += 0.8;
            }
        }
        
        // Fallback: word overlap simples entre query e description
        if (!descLower.isEmpty()) {
            String[] queryWords = queryLower.split("\\s+");
            String[] descWords = descLower.split("\\s+");
            
            long commonWords = Arrays.stream(queryWords)
                .filter(word -> word.length() > 2) // ignorar palavras muito pequenas
                .filter(word -> Arrays.asList(descWords).contains(word))
                .count();
            
            if (commonWords > 0) {
                double overlap = (double) commonWords / Math.max(queryWords.length, descWords.length);
                score += overlap * 0.5;
            }
        }
        
        return Math.min(score, 1.0);
    }
    
    /**
     * Matching baseado em padrões de nomes de ferramentas (actions universais)
     */
    private double namePatternMatch(String query, String toolName) {
        String queryLower = query.toLowerCase();
        String nameLower = toolName != null ? toolName.toLowerCase() : "";
        
        // Actions universais de leitura/obtenção
        if (queryLower.matches(".*(?:get|show|list|mostre|liste|veja|see|display|fetch).*")) {
            if (nameLower.matches(".*(?:get|list|show|fetch|read|display|find).*")) {
                return 0.8;
            }
        }
        
        // Actions universais de criação
        if (queryLower.matches(".*(?:create|crie|make|faça|new|novo|add|adicione).*")) {
            if (nameLower.matches(".*(?:create|write|make|add|new|post|insert).*")) {
                return 0.8;
            }
        }
        
        // Actions universais de leitura/abertura
        if (queryLower.matches(".*(?:read|leia|open|abra|load|carregue).*")) {
            if (nameLower.matches(".*(?:read|get|fetch|load|open).*")) {
                return 0.8;
            }
        }
        
        // Actions universais de atualização
        if (queryLower.matches(".*(?:update|atualize|edit|edite|modify|modifique).*")) {
            if (nameLower.matches(".*(?:update|edit|modify|change|set).*")) {
                return 0.8;
            }
        }
        
        // Actions universais de remoção
        if (queryLower.matches(".*(?:delete|delete|remove|remova|clear|limpe).*")) {
            if (nameLower.matches(".*(?:delete|remove|clear|clean).*")) {
                return 0.8;
            }
        }
        
        return 0.0;
    }
    
    /**
     * Retorna taxa de sucesso histórica da ferramenta
     */
    private double getSuccessRate(String toolName) {
        return successHistory.getOrDefault(toolName, 0.5); // default neutro
    }
    
    /**
     * Limpa histórico de aprendizado (para reset/testing)
     */
    public void clearHistory() {
        successHistory.clear();
        logger.info("[DYNAMIC_MATCHER] Learning history cleared");
    }
    
    /**
     * Retorna estatísticas do matcher para debugging
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("toolsLearned", successHistory.size());
        stats.put("averageSuccessRate", successHistory.values().stream()
            .mapToDouble(Double::doubleValue)
            .average().orElse(0.5));
        stats.put("threshold", MATCH_THRESHOLD);
        return stats;
    }
}
