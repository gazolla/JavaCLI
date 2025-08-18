package com.gazapps.inference.reflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resultado de uma avaliação no processo de reflexão.
 * Contém score, feedback e sugestões de melhoria.
 */
public class ReflectionResult {
    
    private final double overallScore;      // 0.0 - 1.0
    private final Map<String, Double> criteriaScores;
    private final String feedback;
    private final List<String> suggestions;
    private final boolean needsImprovement;
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public ReflectionResult(double overallScore, Map<String, Double> criteriaScores, 
                          String feedback, List<String> suggestions, boolean needsImprovement) {
        this.overallScore = overallScore;
        this.criteriaScores = criteriaScores != null ? new HashMap<>(criteriaScores) : new HashMap<>();
        this.feedback = feedback;
        this.suggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
        this.needsImprovement = needsImprovement;
    }
    
    public double getOverallScore() {
        return overallScore;
    }
    
    public Map<String, Double> getCriteriaScores() {
        return new HashMap<>(criteriaScores);
    }
    
    public String getFeedback() {
        return feedback;
    }
    
    public List<String> getSuggestions() {
        return new ArrayList<>(suggestions);
    }
    
    public boolean needsImprovement() {
        return needsImprovement;
    }
    
    /**
     * Factory method para criar resultado satisfatório
     */
    public static ReflectionResult satisfactory(double score) {
        return new ReflectionResult(score, Map.of(), "Response meets quality standards", 
                                  List.of(), false);
    }
    
    /**
     * Tenta fazer parse da resposta do LLM em formato JSON
     */
    public static ReflectionResult parse(String llmResponse) {
        try {
            // Tenta extrair JSON da resposta
            String jsonContent = extractJsonFromResponse(llmResponse);
            if (jsonContent == null) {
                return parseSimpleResponse(llmResponse);
            }
            
            JsonNode root = objectMapper.readTree(jsonContent);
            
            double overallScore = root.path("overall_score").asDouble(0.5);
            String feedback = root.path("feedback").asText("No feedback provided");
            boolean needsImprovement = root.path("needs_improvement").asBoolean(overallScore < 0.8);
            
            // Parse criteria scores
            Map<String, Double> criteriaScores = new HashMap<>();
            JsonNode criteriaNode = root.path("criteria_scores");
            if (criteriaNode.isObject()) {
                criteriaNode.fields().forEachRemaining(entry -> {
                    criteriaScores.put(entry.getKey(), entry.getValue().asDouble(0.0));
                });
            }
            
            // Parse suggestions
            List<String> suggestions = new ArrayList<>();
            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray()) {
                suggestionsNode.forEach(node -> suggestions.add(node.asText()));
            }
            
            return new ReflectionResult(overallScore, criteriaScores, feedback, suggestions, needsImprovement);
            
        } catch (Exception e) {
            throw ReflectionException.parsingFailed("Failed to parse LLM evaluation response: " + e.getMessage(), llmResponse);
        }
    }
    
    /**
     * Extrai JSON da resposta do LLM (remove texto antes/depois)
     */
    private static String extractJsonFromResponse(String response) {
        if (response == null) return null;
        
        int startIndex = response.indexOf('{');
        int endIndex = response.lastIndexOf('}');
        
        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }
        
        return null;
    }
    
    /**
     * Parse simples quando não há JSON válido
     */
    private static ReflectionResult parseSimpleResponse(String response) {
        // KISS: More conservative scoring
        double score = 0.4; // default lower
        if (response.toLowerCase().contains("excellent") || response.toLowerCase().contains("perfect")) {
            score = 0.8;  // Lower max
        } else if (response.toLowerCase().contains("good") || response.toLowerCase().contains("satisfactory")) {
            score = 0.6;  // Lower good
        } else if (response.toLowerCase().contains("poor") || response.toLowerCase().contains("inadequate")) {
            score = 0.3;
        }
        
        boolean needsImprovement = score < 0.6;  // Lower threshold
        
        return new ReflectionResult(score, Map.of(), response, List.of(), needsImprovement);
    }
    
    @Override
    public String toString() {
        return String.format("ReflectionResult{score=%.2f, needsImprovement=%s, feedback='%s', suggestions=%d}", 
                           overallScore, needsImprovement, 
                           feedback != null ? feedback.substring(0, Math.min(50, feedback.length())) : "null",
                           suggestions.size());
    }
}
