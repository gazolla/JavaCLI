package com.gazapps.inference.reflection;

/**
 * Exception única para todos os erros relacionados ao padrão Reflection.
 * Fornece contexto específico através de campos como fase, iteração e query.
 */
public class ReflectionException extends RuntimeException {
    
    private final String phase;          // "evaluation", "improvement", "parsing", "timeout"
    private final int iteration;         // Em qual iteração ocorreu (-1 se não aplicável)
    private final String query;          // Query original (para contexto)
    
    // Construtor básico
    public ReflectionException(String message, String phase) {
        super(message);
        this.phase = phase;
        this.iteration = -1;
        this.query = null;
    }
    
    // Construtor com causa
    public ReflectionException(String message, String phase, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.iteration = -1;
        this.query = null;
    }
    
    // Construtor com iteração
    public ReflectionException(String message, String phase, int iteration) {
        super(message);
        this.phase = phase;
        this.iteration = iteration;
        this.query = null;
    }
    
    // Construtor completo
    public ReflectionException(String message, String phase, int iteration, String query) {
        super(message);
        this.phase = phase;
        this.iteration = iteration;
        this.query = query;
    }
    
    public String getPhase() {
        return phase;
    }
    
    public int getIteration() {
        return iteration;
    }
    
    public String getQuery() {
        return query;
    }
    
    // Factory methods para cenários comuns
    public static ReflectionException evaluationFailed(String reason, int iteration) {
        return new ReflectionException("Evaluation failed: " + reason, "evaluation", iteration);
    }
    
    public static ReflectionException improvementFailed(String reason, int iteration) {
        return new ReflectionException("Improvement failed: " + reason, "improvement", iteration);
    }
    
    public static ReflectionException parsingFailed(String reason, String content) {
        return new ReflectionException("Parsing failed: " + reason + " (content: " + 
                                     (content != null ? content.substring(0, Math.min(100, content.length())) : "null") + ")", 
                                     "parsing");
    }
    
    public static ReflectionException timeout(int iteration) {
        return new ReflectionException("Reflection timeout reached", "timeout", iteration);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ReflectionException{");
        sb.append("phase='").append(phase).append('\'');
        if (iteration >= 0) {
            sb.append(", iteration=").append(iteration);
        }
        if (query != null) {
            sb.append(", query='").append(query.substring(0, Math.min(50, query.length()))).append("...'");
        }
        sb.append(", message='").append(getMessage()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
