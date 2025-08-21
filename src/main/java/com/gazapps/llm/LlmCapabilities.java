package com.gazapps.llm;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Documenta as capacidades específicas de cada provedor LLM.
 * Permite que as inferências adaptem seu comportamento baseado
 * nas capacidades disponíveis.
 */
public final class LlmCapabilities {
    
    private final boolean supportsFunctionCalling;
    private final boolean supportsSystemMessages;
    private final boolean supportsStreaming;
    private final int maxTokens;
    private final Set<String> supportedFormats;
    
    public LlmCapabilities(boolean supportsFunctionCalling, boolean supportsSystemMessages, 
                          boolean supportsStreaming, int maxTokens, Set<String> supportedFormats) {
        this.supportsFunctionCalling = supportsFunctionCalling;
        this.supportsSystemMessages = supportsSystemMessages;
        this.supportsStreaming = supportsStreaming;
        this.maxTokens = maxTokens;
        this.supportedFormats = supportedFormats != null ? Set.copyOf(supportedFormats) : Collections.emptySet();
    }
    
    /**
     * Builder para criar LlmCapabilities de forma fluida.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private boolean supportsFunctionCalling = false;
        private boolean supportsSystemMessages = true;
        private boolean supportsStreaming = false;
        private int maxTokens = 4096;
        private Set<String> supportedFormats = Set.of("text");
        
        public Builder functionCalling(boolean supported) {
            this.supportsFunctionCalling = supported;
            return this;
        }
        
        public Builder systemMessages(boolean supported) {
            this.supportsSystemMessages = supported;
            return this;
        }
        
        public Builder streaming(boolean supported) {
            this.supportsStreaming = supported;
            return this;
        }
        
        public Builder maxTokens(int tokens) {
            this.maxTokens = tokens;
            return this;
        }
        
        public Builder supportedFormats(Set<String> formats) {
            this.supportedFormats = formats != null ? Set.copyOf(formats) : Collections.emptySet();
            return this;
        }
        
        public LlmCapabilities build() {
            return new LlmCapabilities(supportsFunctionCalling, supportsSystemMessages, 
                                     supportsStreaming, maxTokens, supportedFormats);
        }
    }
    
    // Getters
    
    public boolean supportsFunctionCalling() {
        return supportsFunctionCalling;
    }
    
    public boolean supportsSystemMessages() {
        return supportsSystemMessages;
    }
    
    public boolean supportsStreaming() {
        return supportsStreaming;
    }
    
    public int getMaxTokens() {
        return maxTokens;
    }
    
    public Set<String> getSupportedFormats() {
        return supportedFormats;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        LlmCapabilities that = (LlmCapabilities) obj;
        return supportsFunctionCalling == that.supportsFunctionCalling &&
               supportsSystemMessages == that.supportsSystemMessages &&
               supportsStreaming == that.supportsStreaming &&
               maxTokens == that.maxTokens &&
               Objects.equals(supportedFormats, that.supportedFormats);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(supportsFunctionCalling, supportsSystemMessages, 
                          supportsStreaming, maxTokens, supportedFormats);
    }
    
    @Override
    public String toString() {
        return String.format("LlmCapabilities{functionCalling=%s, systemMessages=%s, streaming=%s, maxTokens=%d, formats=%s}",
                           supportsFunctionCalling, supportsSystemMessages, supportsStreaming, maxTokens, supportedFormats);
    }
}
