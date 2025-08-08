package com.gazapps.inference;

public interface Inference extends AutoCloseable {
    
    String processQuery(String query);
    
    String buildSystemPrompt();
    
    // Default implementation para não quebrar implementações existentes
    @Override
    default void close() {
        // Default: não faz nada
    }
}
