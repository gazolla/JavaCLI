package com.gazapps.inference.simple;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Representa o resultado do matching entre uma query e uma ferramenta.
 * Contém a ferramenta correspondente e o nível de confiança do match.
 */
public class MatchResult {
    
    private final Tool tool;
    private final double confidence;
    
    public MatchResult(Tool tool, double confidence) {
        this.tool = tool;
        this.confidence = confidence;
    }
    
    /**
     * Retorna a ferramenta correspondente.
     */
    public Tool getTool() {
        return tool;
    }
    
    /**
     * Retorna o nível de confiança do match (0.0 a 1.0).
     */
    public double getConfidence() {
        return confidence;
    }
    
    /**
     * Indica se esta ferramenta deve ser usada baseado na confiança.
     */
    public boolean shouldUseTool() {
        return confidence > 0.7 && tool != null;
    }
    
    /**
     * Indica se esta ferramenta é um candidato viável (threshold menor para multi-step).
     */
    public boolean isViableCandidate() {
        return confidence > 0.5 && tool != null;
    }
    
    @Override
    public String toString() {
        return String.format("MatchResult{tool=%s, confidence=%.2f}", 
            tool != null ? tool.name() : "null", confidence);
    }
}
