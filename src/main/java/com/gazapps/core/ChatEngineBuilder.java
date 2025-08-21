package com.gazapps.core;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.inference.Inference;
import com.gazapps.inference.InferenceFactory;
import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmBuilder;

/**
 * ChatEngineBuilder refatorado para trabalhar apenas com a interface Llm.
 * Remove conhecimento de implementações específicas de LLM.
 */
public class ChatEngineBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ChatEngineBuilder.class);

    private Llm llmService;
    private Inference inference;
    private ConversationMemory memory;
    private final com.gazapps.mcp.MCPService mcpService;
    private final com.gazapps.mcp.MCPServers mcpServers;
  
    public com.gazapps.mcp.MCPServers getMcpServers() {
        return mcpServers;
    }

    public com.gazapps.mcp.MCPService getMcpService() {
        return mcpService;
    }
    
    public enum LlmProvider {
        GEMINI,
        GROQ,
        OPENAI,
        CLAUDE;
        
        public static LlmProvider fromString(String value) {
            if (value == null) return null;
            for (LlmProvider provider : LlmProvider.values()) {
                if (provider.name().equalsIgnoreCase(value.trim())) {
                    return provider;
                }
            }
            throw new IllegalArgumentException("Invalid LLM Provider: " + value);
        }
    }
    
    public enum InferenceStrategy {
        SIMPLE,
        REACT,
        REFLECTION;
    	
        public static InferenceStrategy fromString(String value) {
            if (value == null) return null;
            for (InferenceStrategy strategy : InferenceStrategy.values()) {
                if (strategy.name().equalsIgnoreCase(value.trim())) {
                    return strategy;
                }
            }
            throw new IllegalArgumentException("Invalid Inference Strategy: " + value);
        }
    }

    private ChatEngineBuilder() {
        try {
            this.mcpService = new com.gazapps.mcp.MCPService();
            this.mcpServers = new com.gazapps.mcp.MCPServers(this.mcpService);
            
            this.mcpServers.loadServers();
            this.mcpServers.processServerDependencies();
            this.mcpServers.connectToServers();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao inicializar serviços MCP: " + e.getMessage(), e);
        }
    }
    
    public static ChatEngineBuilder create() {
        return new ChatEngineBuilder();
    }
    

    public ChatEngineBuilder llm(Llm llmService) {
        this.llmService = llmService;
        return this;
    }
    

    public ChatEngineBuilder llm(LlmProvider provider, String apiKey) {
        switch (provider) {
            case GEMINI:
                this.llmService = LlmBuilder.createGemini(apiKey);
                break;
            case GROQ:
                this.llmService = LlmBuilder.createGroq(apiKey);
                break;
            case CLAUDE:
                this.llmService = LlmBuilder.createClaude(apiKey);
                break;
            case OPENAI:
                this.llmService = LlmBuilder.createOpenAI(apiKey);
                break;
            default:
                throw new IllegalArgumentException("Provider não suportado: " + provider);
        }
        return this;
    }
    
 
    public ChatEngineBuilder llm(String providerName, String apiKey) {
        LlmProvider provider = LlmProvider.fromString(providerName);
        return llm(provider, apiKey);
    }
    
    public ChatEngineBuilder inference(Inference inference) {
        this.inference = inference;
        return this;
    }
    
    public ChatEngineBuilder inference(InferenceStrategy strategy, Map<String, Object> options) {
        if (llmService == null) {
            throw new IllegalStateException("LLM deve ser configurado antes da inferência");
        }
        
        this.inference = InferenceFactory.createInference(
            strategy, 
            llmService, 
            mcpService, 
            mcpServers, 
            options
        );
        return this;
    }
    

    public ChatEngineBuilder inference(String strategyName, Map<String, Object> options) {
        InferenceStrategy strategy = InferenceStrategy.fromString(strategyName);
        return inference(strategy, options);
    }
    
    public ChatEngineBuilder memory(ConversationMemory memory) {
        this.memory = memory;
        return this;
    }
    
    public ChatEngine build() {
        validateConfiguration();
        
        if (memory == null) {
            memory = new InMemoryConversationMemory();
        }
        
        if (inference == null) {
            Map<String, Object> defaultOptions = Map.of("debug", false);
            inference = InferenceFactory.createInference(
                InferenceStrategy.SIMPLE, 
                llmService, 
                mcpService, 
                mcpServers, 
                defaultOptions
            );
        }
        
        ChatEngine engine = new ChatEngine(llmService, inference, memory, mcpService, mcpServers);
        
        logger.info("ChatEngine built successfully with LLM: {}, Inference: {}", 
                   llmService.getProviderName(), inference.getStrategyName());
        
        return engine;
    }
    
    private void validateConfiguration() {
        if (llmService == null) {
            throw new IllegalArgumentException("LLM é obrigatório");
        }

        if (!llmService.isHealthy()) {
            logger.warn("LLM {} pode não estar funcionando corretamente", llmService.getProviderName());
        }
        
        if (mcpService == null) {
            throw new IllegalArgumentException("MCPService é obrigatório");
        }
        
        if (mcpServers == null) {
            throw new IllegalArgumentException("MCPServers é obrigatório");
        }
    }
    
    /**
     * Método utilitário para criar um ChatEngine com configuração mínima.
     */
    public static ChatEngine createSimple(LlmProvider provider, String apiKey) {
        return create()
            .llm(provider, apiKey)
            .inference(InferenceStrategy.SIMPLE, Map.of("debug", false))
            .memory(new InMemoryConversationMemory())
            .build();
    }
    
    /**
     * Método utilitário para criar um ChatEngine com configuração mínima usando strings.
     */
    public static ChatEngine createSimple(String providerName, String apiKey) {
        LlmProvider provider = LlmProvider.fromString(providerName);
        return createSimple(provider, apiKey);
    }
    
    @Override
    public String toString() {
        return String.format("ChatEngineBuilder{llm=%s, inference=%s, memory=%s}", 
                           llmService != null ? llmService.getProviderName() : "null",
                           inference != null ? inference.getStrategyName() : "null",
                           memory != null ? memory.getClass().getSimpleName() : "null");
    }

	public static ChatEngine currentSetup(LlmProvider currentProvider, InferenceStrategy currentStrategy, Llm llmService) {
		return create()
				.llm(llmService)
				.inference(currentStrategy, Map.of("debug", false))
				.memory(new InMemoryConversationMemory())
	            .build();
	}
}
