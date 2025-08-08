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
    
    public static Llm gemini(String apiKey) {
        return create()
                .provider("gemini")
                .apiKey(apiKey)
                .build();
    }
    
    public static Llm claude(String apiKey) {
        return create()
                .provider("claude")
                .apiKey(apiKey)
                .build();
    }
    
    public static Llm groq(String apiKey) {
        return create()
                .provider("groq")
                .apiKey(apiKey)
                .build();
    }
    
    public static Llm openai(String apiKey) {
        return create()
                .provider("openai")
                .apiKey(apiKey)
                .build();
    }
    
    @Override
    public String toString() {
        return String.format("LLMServiceBuilder{provider='%s', model='%s', timeout=%d, debug=%b, apiKey=%s}",
                provider, model, timeout, debug, 
                apiKey != null ? "***" : "null");
    }
}
