package com.gazapps.inference;

import com.gazapps.core.ChatEngineBuilder.InferenceStrategy;

public interface Inference extends AutoCloseable {
    
    String processQuery(String query);
    
    String buildSystemPrompt();
    
    InferenceStrategy getStrategyName();
    
    // Default implementation para não quebrar implementações existentes
    @Override
    default void close() {
        // Default: não faz nada
    }
}
