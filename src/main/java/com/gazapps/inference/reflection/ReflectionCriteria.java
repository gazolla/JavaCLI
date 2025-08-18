package com.gazapps.inference.reflection;

/**
 * Critérios e templates de prompts para o processo de reflexão.
 * Centraliza a lógica de avaliação e melhoria.
 */
public class ReflectionCriteria {
    
    // Critérios de avaliação
    public static final String ACCURACY = "accuracy";
    public static final String COMPLETENESS = "completeness"; 
    public static final String TOOL_USAGE = "tool_usage";
    public static final String COHERENCE = "coherence";
    
    /**
     * Constrói prompt de avaliação para o LLM
     */
    public static String buildEvaluationPrompt(String query, String response, String toolContext) {
        return String.format("""
            You are evaluating an AI assistant response. Rate it carefully.
            
            ORIGINAL QUERY: %s
            RESPONSE TO EVALUATE: %s
            AVAILABLE TOOLS: %s
            
            CRITICAL RULE: If the query needs tools (file, weather, time operations) but response contains NO FUNCTION_CALL or tool results, set tool_usage=0.0 and overall_score≤0.5
            
            EVALUATION CRITERIA (rate each 0.0-1.0):
            - accuracy: How factually correct?
            - completeness: Fully addresses query?
            - tool_usage: Tools used when needed? (Check for FUNCTION_CALL: or tool results)
            - coherence: Clear and structured?
            
            JSON RESPONSE:
            {
                "overall_score": 0.85,
                "criteria_scores": {
                    "accuracy": 0.9,
                    "completeness": 0.8,
                    "tool_usage": 0.9,
                    "coherence": 0.8
                },
                "feedback": "Brief feedback",
                "suggestions": ["Improvement 1", "Improvement 2"],
                "needs_improvement": true
            }
            
            JSON only:""", query, response, toolContext);
    }
    
    /**
     * Constrói prompt de melhoria para o LLM
     */
    public static String buildImprovementPrompt(String query, String originalResponse, 
                                              ReflectionResult evaluation, String toolContext) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Improve this AI response based on feedback.\n\n");
        
        prompt.append("ORIGINAL QUERY: ").append(query).append("\n\n");
        prompt.append("ORIGINAL RESPONSE: ").append(originalResponse).append("\n\n");
        
        prompt.append("FEEDBACK: ").append(evaluation.getFeedback()).append("\n");
        
        if (!evaluation.getSuggestions().isEmpty()) {
            prompt.append("SUGGESTIONS: ");
            for (String suggestion : evaluation.getSuggestions()) {
                prompt.append(suggestion).append("; ");
            }
            prompt.append("\n");
        }
        
        prompt.append("\nAVAILABLE TOOLS: ").append(toolContext).append("\n\n");
        
        prompt.append("IMPORTANT: If tools are needed, use FUNCTION_CALL:tool_name:{\"param\":\"value\"}\n");
        prompt.append("Examples: FUNCTION_CALL:secure-filesystem-server_write_file:{\"path\":\"C:\\\\Users\\\\gazol\\\\Documents\\\\file.txt\",\"content\":\"text\"}\n\n");
        
        prompt.append("IMPROVED RESPONSE:");
        
        return prompt.toString();
    }
    
    /**
     * Prompt para resposta inicial com contexto de ferramentas
     */
    public static String buildInitialPrompt(String query, String toolContext) {
        return String.format("""
            You are a helpful AI assistant with access to tools. Answer the user's query.
            
            USER QUERY: %s
            
            AVAILABLE TOOLS: %s
            
            IMPORTANT: When you need to use tools, respond with:
            FUNCTION_CALL:tool_name:{"parameter":"value"}
            
            Examples:
            - For file creation: FUNCTION_CALL:secure-filesystem-server_write_file:{"path":"C:\\Users\\gazol\\Documents\\filename.txt","content":"text"}
            - For weather: FUNCTION_CALL:weather-nws_get-forecast:{"latitude":40.7128,"longitude":-74.0060}
            - For time: FUNCTION_CALL:mcp-time_get_current_time:{"timezone":"America/Sao_Paulo"}
            
            Use tools when the query requires them. Respond naturally otherwise.
            
            RESPONSE:""", query, toolContext);
    }
    
    /**
     * Determina se uma resposta precisa de ferramentas baseado na query
     */
    public static boolean queryNeedsTools(String query) {
        String lowerQuery = query.toLowerCase();
        
        // Indica necessidade de filesystem
        if (lowerQuery.contains("file") || lowerQuery.contains("save") || 
            lowerQuery.contains("create") || lowerQuery.contains("read") ||
            lowerQuery.contains("arquivo") || lowerQuery.contains("salvar")) {
            return true;
        }
        
        // Indica necessidade de weather
        if (lowerQuery.contains("weather") || lowerQuery.contains("temperature") || 
            lowerQuery.contains("clima") || lowerQuery.contains("temperatura")) {
            return true;
        }
        
        // Indica necessidade de memory/storage
        if (lowerQuery.contains("remember") || lowerQuery.contains("store") || 
            lowerQuery.contains("lembrar") || lowerQuery.contains("armazenar")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Score threshold padrão para considerar resposta satisfatória
     */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.6;  // KISS: Lower threshold
    
    /**
     * Máximo de iterações padrão
     */
    public static final int DEFAULT_MAX_ITERATIONS = 3;
}
