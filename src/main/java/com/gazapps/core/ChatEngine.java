package com.gazapps.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.exceptions.MCPException;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmException;
import com.gazapps.mcp.MCPServers;
import com.gazapps.mcp.MCPService;

/**
 * ChatEngine refatorado para trabalhar apenas com a interface Llm.
 * Remove conhecimento de implementações específicas de LLM.
 */
public class ChatEngine implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatEngine.class);
    
    private final Llm llmService;
    private final Inference inference;
    private final ConversationMemory memory;
    private final MCPService mcpService;
    private final MCPServers mcpServers;
    
    public ChatEngine(Llm llmService, Inference inference, ConversationMemory memory, 
                     MCPService mcpService, MCPServers mcpServers) {
        this.llmService = llmService;
        this.inference = inference;
        this.memory = memory;
        this.mcpService = mcpService;
        this.mcpServers = mcpServers;
        
        // Configurar memória nas inferências que suportam
        configureInferenceMemory();
        
        logger.info("ChatEngine inicializado com LLM: {} ({}), Inference: {}", 
                   llmService.getProviderName(), 
                   llmService.getCapabilities(),
                   inference.getStrategyName());
    }
    
    /**
     * Configuração compatível com versão anterior.
     */
    public ChatEngine(Llm llmService, Inference inference, ConversationMemory memory, MCPServers mcpServers) {
        this(llmService, inference, memory, null, mcpServers);
    }

    /**
     * Configuração simples.
     */
    public ChatEngine(Llm llmService, Inference inference, ConversationMemory memory) {
        this(llmService, inference, memory, null, null);
    }
    
    private void configureInferenceMemory() {
        // Usar reflexão para configurar memória sem acoplamento
        try {
            var setMemoryMethod = inference.getClass().getMethod("setConversationMemory", Object.class);
            setMemoryMethod.invoke(inference, memory);
            logger.debug("Configured conversation memory for inference: {}", inference.getStrategyName());
        } catch (Exception e) {
            // Não é um erro, algumas inferências podem não ter memória
            logger.debug("Inference {} does not support conversation memory", inference.getStrategyName());
        }
    }
    
    public String processQuery(String query) {
        try {
            logger.debug("Processing query with {}: {}", llmService.getProviderName(), 
                        query.length() > 100 ? query.substring(0, 100) + "..." : query);
            
            // Verificar saúde do LLM antes de processar
            if (!llmService.isHealthy()) {
                logger.warn("LLM {} may not be functioning properly", llmService.getProviderName());
            }
            
            if (memory != null) {
                memory.addMessage("user", query);
            }
            
            String response = inference.processQuery(query);
            
            if (memory != null && response != null) {
                memory.addMessage("assistant", response);
            }
            
            logger.debug("Response generated successfully by {}", llmService.getProviderName());
            return response;
            
        } catch (LlmException e) {
            String detailedMessage = String.format("LLM Error [%s/%s]: %s", 
                                                  e.getProvider(), e.getErrorType(), e.getMessage());
            logger.error("LLM error processing query: {}", detailedMessage, e);
            throw new RuntimeException(detailedMessage, e);
            
        } catch (Exception e) {
            String detailedMessage = getDetailedErrorMessage(e);
            logger.error("Unexpected error processing query: {}", detailedMessage, e);
            throw new RuntimeException(detailedMessage, e);
        }
    }
    
    private String getDetailedErrorMessage(Throwable e) {
        Throwable cause = e;
        String errorMessage = e.getMessage();
        
        // Percorrer a cadeia de causas para encontrar a mensagem mais específica
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                errorMessage = cause.getMessage();
            }
            cause = cause.getCause();
        }
        
        return errorMessage != null ? errorMessage : "Unknown error occurred";
    }
    
    /**
     * Retorna informações sobre o LLM em uso.
     */
    public String getLLMInfo() {
        return String.format("%s (capabilities: %s, healthy: %s)", 
                           llmService.getProviderName(),
                           llmService.getCapabilities(),
                           llmService.isHealthy());
    }
    
    /**
     * Retorna informações sobre a estratégia de inferência.
     */
    public String getInferenceInfo() {
        return inference.getStrategyName().name();
    }
    
    /**
     * Verifica se o sistema está funcionando corretamente.
     */
    public boolean isHealthy() {
        try {
            boolean llmHealthy = llmService.isHealthy();
            boolean mcpHealthy = (mcpServers == null) || mcpServers.getConnectedServers().size() > 0;
            
            return llmHealthy && mcpHealthy;
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return false;
        }
    }
    
    /**
     * Executa diagnóstico do sistema.
     */
    public String diagnose() {
        StringBuilder diagnosis = new StringBuilder();
        
        diagnosis.append("=== ChatEngine Diagnostics ===\n");
        diagnosis.append("LLM Provider: ").append(llmService.getProviderName()).append("\n");
        diagnosis.append("LLM Capabilities: ").append(llmService.getCapabilities()).append("\n");
        diagnosis.append("LLM Healthy: ").append(llmService.isHealthy()).append("\n");
        diagnosis.append("Inference Strategy: ").append(inference.getStrategyName()).append("\n");
        
        if (mcpServers != null) {
            diagnosis.append("MCP Servers Connected: ").append(mcpServers.getConnectedServers().size()).append("\n");
        } else {
            diagnosis.append("MCP Servers: Not configured\n");
        }
        
        if (memory != null) {
            diagnosis.append("Memory: ").append(memory.getClass().getSimpleName()).append("\n");
        } else {
            diagnosis.append("Memory: Not configured\n");
        }
        
        diagnosis.append("Overall Health: ").append(isHealthy() ? "✅ Healthy" : "❌ Issues detected");
        
        return diagnosis.toString();
    }
    
    // Getters para compatibilidade
    
    public Llm getLLMService() {
        return llmService;
    }
    
    public Inference getInference() {
        return inference;
    }
    
    public ConversationMemory getMemory() {
        return memory;
    }
    
    public MCPService getMcpService() {
        return mcpService;
    }
    
    public MCPServers getMcpServers() {
        return mcpServers;
    }
    
    @Override
    public void close() {
        try {
            if (inference != null) {
                inference.close();
                logger.debug("Inference closed");
            }
        } catch (Exception e) {
            logger.warn("Error closing inference", e);
        }
        
        try {
            if (mcpServers != null) {
                mcpServers.close();
                logger.debug("MCP servers closed");
            }
        } catch (Exception e) {
            logger.warn("Error closing MCP servers", e);
        }
        
        logger.info("ChatEngine closed");
    }
}
