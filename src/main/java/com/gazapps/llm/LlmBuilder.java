package com.gazapps.llm;

import java.util.HashMap;
import java.util.Map;

public class LlmBuilder {
    
    private String provider;
    private String apiKey;
    private String model;
    private int timeout = 30;
    private boolean debug = false;
    
    private LlmBuilder() {}
    
    public static LlmBuilder create() {
        return new LlmBuilder();
    }
    
    public LlmBuilder provider(String provider) {
        this.provider = provider;
        return this;
    }
    
    public LlmBuilder apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }
    
    public LlmBuilder model(String model) {
        this.model = model;
        return this;
    }
    
    public LlmBuilder timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }
    
    public LlmBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }
    
    public Llm build() {
        if (provider == null || provider.trim().isEmpty()) {
            throw new IllegalArgumentException("Provider é obrigatório");
        }
        
        if (timeout <= 0 || timeout > 300) {
            throw new IllegalArgumentException("Timeout deve estar entre 1 e 300 segundos");
        }
        
        switch (provider.toLowerCase()) {
            case "gemini":
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    throw new IllegalArgumentException("API Key é obrigatória para Gemini");
                }
                return createGeminiService();
            case "groq":
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    throw new IllegalArgumentException("API Key é obrigatória para Groq");
                }
                return createGroqService();
            case "claude":
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    throw new IllegalArgumentException("API Key é obrigatória para Claude");
                }
                return createClaudeService();
            case "openai":
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    throw new IllegalArgumentException("API Key é obrigatória para OpenAI");
                }
                return createOpenAIService();
            default:
                throw new IllegalArgumentException("Provider não reconhecido: " + provider);
        }
    }
    
    private Llm createGeminiService() {
        Gemini service = new Gemini();
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        if (model != null) config.put("model", model);
        config.put("timeout", String.valueOf(timeout));
        config.put("debug", String.valueOf(debug));
        service.configure(config);
        return service;
    }
    
    private Llm createGroqService() {
        Groq service = new Groq();
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        if (model != null) config.put("model", model);
        config.put("timeout", String.valueOf(timeout));
        config.put("debug", String.valueOf(debug));
        service.configure(config);
        return service;
    }
    
    private Llm createClaudeService() {
        // Claude implementation - for now use Groq as fallback
        // TODO: Implement proper Claude service
        Groq service = new Groq(); // Temporary fallback
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        if (model != null) config.put("model", model);
        config.put("timeout", String.valueOf(timeout));
        config.put("debug", String.valueOf(debug));
        service.configure(config);
        return service;
    }
    
    private Llm createOpenAIService() {
        // OpenAI implementation - for now use Groq as fallback
        // TODO: Implement proper OpenAI service
        Groq service = new Groq(); // Temporary fallback
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", apiKey);
        if (model != null) config.put("model", model);
        config.put("timeout", String.valueOf(timeout));
        config.put("debug", String.valueOf(debug));
        service.configure(config);
        return service;
    }
    
    public static Llm gemini(String apiKey) {
        // Use provided key or try to get from system properties/environment
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey : getApiKeyFromEnvironment("GEMINI_API_KEY");
        return create()
                .provider("gemini")
                .apiKey(key)
                .build();
    }
    
    public static Llm claude(String apiKey) {
        // Use provided key or try to get from system properties/environment
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey : getApiKeyFromEnvironment("ANTHROPIC_API_KEY");
        return create()
                .provider("claude")
                .apiKey(key)
                .build();
    }
    
    public static Llm groq(String apiKey) {
        // Use provided key or try to get from system properties/environment
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey : getApiKeyFromEnvironment("GROQ_API_KEY");
        return create()
                .provider("groq")
                .apiKey(key)
                .build();
    }
    
    public static Llm openai(String apiKey) {
        // Use provided key or try to get from system properties/environment
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey : getApiKeyFromEnvironment("OPENAI_API_KEY");
        return create()
                .provider("openai")
                .apiKey(key)
                .build();
    }
    
    /**
     * Get API key from system properties first, then environment variables
     */
    private static String getApiKeyFromEnvironment(String envVarName) {
        // Check system properties first (loaded from .env by EnvironmentSetup)
        String key = System.getProperty(envVarName);
        if (key != null && !key.trim().isEmpty()) {
            return key;
        }
        
        // Check environment variables
        key = System.getenv(envVarName);
        if (key != null && !key.trim().isEmpty()) {
            return key;
        }
        
        return null;
    }
    
    @Override
    public String toString() {
        return String.format("LLMServiceBuilder{provider='%s', model='%s', timeout=%d, debug=%b, apiKey=%s}",
                provider, model, timeout, debug, 
                apiKey != null ? "***" : "null");
    }
}
