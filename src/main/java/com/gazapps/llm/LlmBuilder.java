package com.gazapps.llm;

import java.util.HashMap;
import java.util.Map;

import com.gazapps.llm.providers.Claude;
import com.gazapps.llm.providers.Gemini;
import com.gazapps.llm.providers.Groq;
import com.gazapps.llm.providers.OpenAI;
import com.gazapps.core.ChatEngineBuilder.LlmProvider;

/**
 * Builder refatorado para trabalhar apenas com a interface Llm.
 * Remove conhecimento de implementações específicas além do necessário para instanciação.
 */
public class LlmBuilder {
    
    private LlmProvider provider;
    private String apiKey;
    private String model;
    private int timeout = 30;
    private boolean debug = false;
    
    private LlmBuilder() {}
    
    public static LlmBuilder create() {
        return new LlmBuilder();
    }
    
    public LlmBuilder provider(LlmProvider provider) {
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
        validateConfiguration();
        
        switch (provider) {
            case GEMINI:
                return createGemini(apiKey);
            case GROQ:
                return createGroq(apiKey);
            case CLAUDE:
                return createClaude(apiKey);
            case OPENAI:
                return createOpenAI(apiKey);
            default:
                throw new LlmException(provider, LlmException.ErrorType.INVALID_REQUEST, 
                                     "Provider não reconhecido: " + provider.toString());
        }
    }
    
    private void validateConfiguration() {
        if (provider == null) {
            throw new LlmException(null, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Provider é obrigatório");
        }
        
        if (timeout <= 0 || timeout > 300) {
            throw new LlmException(provider, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Timeout deve estar entre 1 e 300 segundos");
        }
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new LlmException(provider, LlmException.ErrorType.AUTHENTICATION, 
                                 "API Key é obrigatória para " + provider);
        }
    }
    
    /**
     * Factory method para criar Gemini com validação de parâmetros.
     */
    public static Llm createGemini(String apiKey) {
        try {
            validateApiKey(apiKey, LlmProvider.GEMINI);
            return new Gemini();
        } catch (Exception e) {
            throw new LlmException(null, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Falha ao criar instância Gemini: " + e.getMessage(), e);
        }
    }
    
    /**
     * Factory method para criar Groq com validação de parâmetros.
     */
    public static Llm createGroq(String apiKey) {
        try {
            validateApiKey(apiKey, LlmProvider.GROQ);
            return new Groq();
        } catch (Exception e) {
            throw new LlmException(null, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Falha ao criar instância Groq: " + e.getMessage(), e);
        }
    }
    
    /**
     * Factory method para criar Claude com validação de parâmetros.
     */
    public static Llm createClaude(String apiKey) {
        try {
            validateApiKey(apiKey, LlmProvider.CLAUDE);
            return new Claude();
        } catch (Exception e) {
            throw new LlmException(null, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Falha ao criar instância Claude: " + e.getMessage(), e);
        }
    }
    
    /**
     * Factory method para criar OpenAI com validação de parâmetros.
     */
    public static Llm createOpenAI(String apiKey) {
        try {
            validateApiKey(apiKey, LlmProvider.OPENAI);
            return new OpenAI();
        } catch (Exception e) {
            throw new LlmException(null, LlmException.ErrorType.INVALID_REQUEST, 
                                 "Falha ao criar instância OpenAI: " + e.getMessage(), e);
        }
    }
    
    private static void validateApiKey(String apiKey, LlmProvider provider) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            // Tentar obter da configuração ou ambiente
            String key = getApiKeyFromEnvironment(getEnvironmentKeyName(provider));
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("API Key é obrigatória para " + provider.name());
            }
        }
    }
    
    private static String getEnvironmentKeyName(LlmProvider provider) {
        switch (provider) {
            case GEMINI: return "GEMINI_API_KEY";
            case GROQ: return "GROQ_API_KEY";
            case CLAUDE: return "ANTHROPIC_API_KEY";
            case OPENAI: return "OPENAI_API_KEY";
            default: return provider.name().toUpperCase() + "_API_KEY";
        }
    }
    
    // Métodos de conveniência que retornam a interface Llm
    
    public static Llm gemini(String apiKey) {
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? 
                     apiKey : getApiKeyFromEnvironment("GEMINI_API_KEY");
        return createGemini(key);
    }
    
    public static Llm claude(String apiKey) {
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? 
                     apiKey : getApiKeyFromEnvironment("ANTHROPIC_API_KEY");
        return createClaude(key);
    }
    
    public static Llm groq(String apiKey) {
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? 
                     apiKey : getApiKeyFromEnvironment("GROQ_API_KEY");
        return createGroq(key);
    }
    
    public static Llm openai(String apiKey) {
        String key = (apiKey != null && !apiKey.trim().isEmpty()) ? 
                     apiKey : getApiKeyFromEnvironment("OPENAI_API_KEY");
        return createOpenAI(key);
    }
    
    /**
     * Obtém API key de propriedades do sistema primeiro, depois variáveis de ambiente.
     */
    private static String getApiKeyFromEnvironment(String envVarName) {
        // Verificar propriedades do sistema primeiro (carregadas do .env pelo EnvironmentSetup)
        String key = System.getProperty(envVarName);
        if (key != null && !key.trim().isEmpty()) {
            return key;
        }
        
        // Verificar variáveis de ambiente
        key = System.getenv(envVarName);
        if (key != null && !key.trim().isEmpty()) {
            return key;
        }
        
        return null;
    }
    
    @Override
    public String toString() {
        return String.format("LlmBuilder{provider='%s', model='%s', timeout=%d, debug=%b, apiKey=%s}",
                provider, model, timeout, debug, 
                apiKey != null ? "***" : "null");
    }
}
