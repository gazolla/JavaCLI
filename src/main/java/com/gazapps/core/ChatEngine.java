package com.gazapps.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.mcp.MCPServers;
import com.gazapps.exceptions.LLMException;
import com.gazapps.exceptions.MCPException;

public class ChatEngine implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatEngine.class);
    
    private final Llm llmService;
    private final Inference inference;
    private final ConversationMemory memory;
    private MCPServers mcpServers;
    
	public ChatEngine(Llm llmService, Inference inference, ConversationMemory memory, MCPServers mcpServers) {
        this(llmService, inference, memory);
        this.mcpServers = mcpServers;
    }

	public ChatEngine(Llm llmService, Inference inference, ConversationMemory memory) {
        this.llmService = llmService;
        this.inference = inference;
        this.memory = memory;
        

        
        if (inference instanceof com.gazapps.inference.simple.SimpleInference) {
            ((com.gazapps.inference.simple.SimpleInference) inference).setConversationMemory(memory);
        } else if (inference instanceof com.gazapps.inference.react.ReActInference) {
            ((com.gazapps.inference.react.ReActInference) inference).setConversationMemory(memory);
        }
        
        logger.info("ChatEngine inicializado com LLM: {} e Inference: {}", 
                   llmService.getProviderName(), inference.getClass().getSimpleName());
    }
    
    public String processQuery(String query) {
        try {
            logger.debug("Processing query: {}", query);
            
            if (memory != null) {
                memory.addMessage("user", query);
            }
            
            String response = inference.processQuery(query);
            
            if (memory != null && response != null) {
                memory.addMessage("assistant", response);
            }
            
            logger.debug("Response generated successfully");
            return response;
            
        } catch (Exception e) {
            logger.error("Error processing query: {}", e.getMessage(), e);
            
            // Re-throw with specific context if it's already a specific exception
            if (e instanceof LLMException || e instanceof MCPException) {
                throw new RuntimeException(e); // Preserve specific exception
            } else {
                throw new RuntimeException("Query processing error", e);
            }
        }
    }
    
    public Llm getLLMService() {
        return llmService;
    }
    
    public Inference getInference() {
        return inference;
    }
    
    public ConversationMemory getMemory() {
        return memory;
    }
    
    public MCPServers getMcpServers() {
		return mcpServers;
	}

    @Override
    public void close() {
    	
        if (inference instanceof AutoCloseable) {
            try {
                ((AutoCloseable) inference).close();
            } catch (Exception e) {
                logger.warn("Erro ao fechar inference: {}", e.getMessage());
            }
        }
    }
}
