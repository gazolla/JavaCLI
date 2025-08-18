package com.gazapps.config;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.core.ChatEngineBuilder.InferenceStrategy;
import com.gazapps.core.ChatEngineBuilder.LlmProvider;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;

public class RuntimeConfigManager {
    
    private static final Logger logger = LoggerFactory.getLogger(RuntimeConfigManager.class);
    
    private LlmProvider currentProvider;
    private InferenceStrategy currentStrategy;
    private Map<String, Object> configCache = new HashMap<>();
    
    public RuntimeConfigManager() {
       // loadCurrentConfiguration();
    }
    
    public RuntimeConfigManager(String llmService, String inference) {
		this.currentProvider = LlmProvider.fromString(llmService);
		this.currentStrategy = InferenceStrategy.fromString(inference);
	}

	private void loadCurrentConfiguration() {
        // Load from environment or defaults
        String envProvider = System.getenv("DEFAULT_LLM_PROVIDER");
        if (envProvider != null) {
            try {
                currentProvider = LlmProvider.valueOf(envProvider.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid LLM provider in environment: {}", envProvider);
            }
        }
        
        String envStrategy = System.getenv("DEFAULT_INFERENCE_STRATEGY");
        if (envStrategy != null) {
            try {
                currentStrategy = InferenceStrategy.valueOf(envStrategy.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid inference strategy in environment: {}", envStrategy);
            }
        }
        
        logger.info("Runtime config loaded - Provider: {}, Strategy: {}", currentProvider, currentStrategy);
    }
    
    public LlmProvider getCurrentProvider() {
        return currentProvider;
    }
    
    public void setCurrentProvider(LlmProvider provider) {
        this.currentProvider = provider;
        logger.info("LLM provider changed to: {}", provider);
    }
    
    public InferenceStrategy getCurrentStrategy() {
        return currentStrategy;
    }
    
    public void setCurrentStrategy(InferenceStrategy strategy) {
        this.currentStrategy = strategy;
        logger.info("Inference strategy changed to: {}", strategy);
    }
    
    public ChatEngine createChatEngine() throws Exception {
        return ChatEngineBuilder.currentSetup(currentProvider, currentStrategy);
    }
    
    public ChatEngine createChatEngine(LlmProvider provider, InferenceStrategy strategy) throws Exception {
        return ChatEngineBuilder.currentSetup(provider, strategy);
    }
    
    public boolean isProviderAvailable(LlmProvider provider) {
        Config config = new Config();
        return config.isLlmConfigValid(provider.name().toLowerCase());
    }
    
    public void cacheConfig(String key, Object value) {
        configCache.put(key, value);
    }
    
    public Object getCachedConfig(String key) {
        return configCache.get(key);
    }
    
    public void clearCache() {
        configCache.clear();
        logger.debug("Configuration cache cleared");
    }
    
    public Map<String, Object> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("currentProvider", currentProvider.name());
        info.put("currentStrategy", currentStrategy.name());
        info.put("cacheSize", configCache.size());
        
        // Check which providers are available
        Map<String, Boolean> providerStatus = new HashMap<>();
        for (LlmProvider provider : LlmProvider.values()) {
            providerStatus.put(provider.name(), isProviderAvailable(provider));
        }
        info.put("providerAvailability", providerStatus);
        
        return info;
    }
    
    public ChatEngine recreateChatEngineWithNewLlm(ChatEngine currentEngine, LlmProvider newProvider) throws Exception {
        logger.info("Changing LLM provider to: {}", newProvider);
        
        // Update current provider
        setCurrentProvider(newProvider);
        
        // Reutilizar os servidores MCP existentes
        com.gazapps.mcp.MCPServers existingMcpServers = currentEngine.getMcpServers();
        com.gazapps.core.ConversationMemory existingMemory = currentEngine.getMemory();
        
        // Criar apenas o novo LLM
        Llm newLlmService = switch (newProvider) {
            case GEMINI -> com.gazapps.llm.LlmBuilder.gemini(null);
            case GROQ -> com.gazapps.llm.LlmBuilder.groq(null);
            case OPENAI -> com.gazapps.llm.LlmBuilder.openai(null);
            case CLAUDE -> com.gazapps.llm.LlmBuilder.claude(null);
        };
        
        // Criar nova inferência com o novo LLM mas usando MCP existente
        Inference newInference = createInference(currentStrategy, newLlmService, existingMcpServers);
        
        // Criar novo ChatEngine reutilizando MCP e memória
        return new com.gazapps.core.ChatEngine(newLlmService, newInference, existingMemory, existingMcpServers);
    }
    
    public ChatEngine recreateChatEngineWithNewInference(ChatEngine currentEngine, InferenceStrategy newStrategy) throws Exception {
        logger.info("Changing inference strategy to: {}", newStrategy);
        
        // Update current strategy
        setCurrentStrategy(newStrategy);
        
        // Reutilizar LLM e servidores MCP existentes
        Llm existingLlmService = currentEngine.getLLMService();
        com.gazapps.mcp.MCPServers existingMcpServers = currentEngine.getMcpServers();
        com.gazapps.core.ConversationMemory existingMemory = currentEngine.getMemory();
        
        // Criar apenas a nova inferência
        Inference newInference = createInference(newStrategy, existingLlmService, existingMcpServers);
        
        // Criar novo ChatEngine reutilizando LLM, MCP e memória
        return new com.gazapps.core.ChatEngine(existingLlmService, newInference, existingMemory, existingMcpServers);
    }
    
    public boolean validateConfiguration(ChatEngine chatEngine) {
        if (chatEngine == null) {
            logger.error("ChatEngine is null");
            return false;
        }
        
        try {
            // Test basic functionality
            String llmProvider = chatEngine.getLLMService().getProviderName();
            String inferenceStrategy = chatEngine.getInference().getClass().getSimpleName();
            
            logger.info("Configuration validated - LLM: {}, Inference: {}", llmProvider, inferenceStrategy);
            return true;
            
        } catch (Exception e) {
            logger.error("Configuration validation failed: {}", e.getMessage());
            return false;
        }
    }
    
    public String getCurrentConfigSummary(ChatEngine chatEngine) {
        StringBuilder summary = new StringBuilder();
        
        try {
            summary.append("\n📊 Current Configuration:\n");
            summary.append("═══════════════════════════\n");
            
            if (chatEngine != null) {
                String llmProvider = chatEngine.getLLMService().getProviderName();
                String inferenceStrategy = chatEngine.getInference().getClass().getSimpleName();
                
                summary.append(String.format("🤖 LLM Provider: %s\n", llmProvider));
                summary.append(String.format("🧠 Inference Strategy: %s\n", inferenceStrategy));
            } else {
                summary.append("❌ ChatEngine not initialized\n");
            }
            
            summary.append(String.format("📁 Workspace: %s\n", EnvironmentSetup.getCurrentWorkspacePath()));
            summary.append(String.format("💾 Config Cache: %d items\n", configCache.size()));
            
            // Provider availability
            summary.append("\n🔑 Provider Status:\n");
            for (LlmProvider provider : LlmProvider.values()) {
                boolean available = isProviderAvailable(provider);
                String status = available ? "✅" : "❌";
                summary.append(String.format("   %s %s\n", status, provider.name()));
            }
            
        } catch (Exception e) {
            summary.append("Error generating summary: ").append(e.getMessage());
        }
        
        return summary.toString();
    }
    
    // Método auxiliar para criar inferência sem reinicializar MCP
    private Inference createInference(InferenceStrategy strategy, Llm llmService, com.gazapps.mcp.MCPServers mcpServers) {
        com.gazapps.mcp.MCPService mcpService = new com.gazapps.mcp.MCPService(); // Apenas o serviço, não as conexões
        
        return switch (strategy) {
            case SIMPLE -> com.gazapps.inference.InferenceFactory.createSimple(llmService, mcpService, mcpServers);
            case REACT -> com.gazapps.inference.InferenceFactory.createReAct(llmService, mcpService, mcpServers, 
                             java.util.Map.of("maxIterations", 5, "debug", true));
            case REFLECTION -> com.gazapps.inference.InferenceFactory.createReflection(llmService, mcpService, mcpServers,
                             java.util.Map.of("maxIterations", 3, "debug", true));
        };
    }
}
