package com.gazapps.inference.reflection;

/**
 * Representa um passo individual no ciclo de reflexão.
 * Usado para logging e debug do processo.
 */
public class ReflectionStep {
    
    public enum StepType { 
        INITIAL_RESPONSE, 
        EVALUATION, 
        IMPROVEMENT, 
        FINAL 
    }
    
    private final StepType type;
    private final String content;
    private final ReflectionResult evaluation;
    private final int iteration;
    private final long timestamp;
    
    public ReflectionStep(StepType type, String content, double score) {
        this(type, content, null, (int) Math.round(score));
    }
    
    public ReflectionStep(StepType type, String content, int iteration) {
        this(type, content, null, iteration);
    }
    
    public ReflectionStep(StepType type, String content, ReflectionResult evaluation, int iteration) {
        this.type = type;
        this.content = content;
        this.evaluation = evaluation;
        this.iteration = iteration;
        this.timestamp = System.currentTimeMillis();
    }
    
    public StepType getType() {
        return type;
    }
    
    public String getContent() {
        return content;
    }
    
    public ReflectionResult getEvaluation() {
        return evaluation;
    }
    
    public int getIteration() {
        return iteration;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("ReflectionStep{type=%s, iteration=%d, contentLength=%d, hasEvaluation=%s}", 
                           type, iteration, 
                           content != null ? content.length() : 0,
                           evaluation != null);
    }
}
