package com.gazapps.exceptions;

/**
 * Exception for LLM service related problems
 */
public class LLMException extends Exception {
    private final String provider;
    
    public LLMException(String message, String provider) {
        super(message);
        this.provider = provider;
    }
    
    public LLMException(String message, String provider, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }
    
    public String getProvider() { 
        return provider; 
    }
}
