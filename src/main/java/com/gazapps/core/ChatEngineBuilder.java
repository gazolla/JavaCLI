package com.gazapps.core;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.inference.Inference;
import com.gazapps.inference.InferenceFactory;
import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmBuilder;

public class ChatEngineBuilder {

private static final Logger logger = LoggerFactory.getLogger(ChatEngineBuilder.class);

    private Llm llmService;
    private Inference inference;
    private ConversationMemory memory;
    
    public enum LlmProvider {
        GEMINI,
        GROQ,
        OPENAI,
        CLAUDE
    }
    public static ChatEngine toolUseSetup(LlmProvider provider) {
        return currentSetup(provider, InferenceStrategy.TOOLUSE);
    }
    
    public static ChatEngine toolUseChainedSetup(LlmProvider provider, int maxChainLength) {
        try {
            com.gazapps.mcp.MCPService mcpService = new com.gazapps.mcp.MCPService();
            com.gazapps.mcp.MCPServers mcpServers = new com.gazapps.mcp.MCPServers(mcpService);
            
            mcpServers.loadServers();
            mcpServers.processServerDependencies();
            mcpServers.connectToServers();
            
            Llm llmService = switch (provider) {
                case GEMINI -> LlmBuilder.gemini(System.getenv("GEMINI_API_KEY"));
                case GROQ -> LlmBuilder.groq(System.getenv("GROQ_API_KEY"));
                case OPENAI -> LlmBuilder.openai(System.getenv("OPENAI_API_KEY"));
                case CLAUDE -> LlmBuilder.claude(System.getenv("CLAUDE_API_KEY"));
            };
            
            Inference inference = InferenceFactory.createToolUseChained(llmService, mcpService, mcpServers, maxChainLength);
            
            return create()
                    .llm(llmService)
                    .inference(inference)
                    .memory(new InMemoryConversationMemory())
                    .build();
                    
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar configuração ToolUse chained: " + e.getMessage(), e);
        }
    }
    
    public enum InferenceStrategy {
        SEQUENTIAL,
        REACT,
        TOOLUSE
    }
    
    private ChatEngineBuilder() {}
    
    public static ChatEngineBuilder create() {
        return new ChatEngineBuilder();
    }
    
    public ChatEngineBuilder llm(Llm llmService) {
        this.llmService = llmService;
        return this;
    }
    
    public ChatEngineBuilder inference(Inference inference) {
        this.inference = inference;
        return this;
    }
    
    public ChatEngineBuilder memory(ConversationMemory memory) {
        this.memory = memory;
        return this;
    }
    
    public ChatEngine build() {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (inference == null) {
            throw new IllegalArgumentException("Inference é obrigatório");
        }
        
        if (memory == null) {
            memory = new InMemoryConversationMemory();
        }
        
        logger.info(this.toString());
        
        return new ChatEngine(llmService, inference, memory);
    }
    
    public static ChatEngine currentSetup(LlmProvider provider) {
        return currentSetup(provider, InferenceStrategy.SEQUENTIAL);
    }
    
    public static ChatEngine currentSetup(LlmProvider provider, InferenceStrategy strategy) {
        try {
            com.gazapps.mcp.MCPService mcpService = new com.gazapps.mcp.MCPService();
            com.gazapps.mcp.MCPServers mcpServers = new com.gazapps.mcp.MCPServers(mcpService);
            
            mcpServers.loadServers();
            mcpServers.processServerDependencies();
            mcpServers.connectToServers();
            
            Llm llmService = switch (provider) {
            case GEMINI -> LlmBuilder.gemini(System.getenv("GEMINI_API_KEY"));
            case GROQ -> LlmBuilder.groq(System.getenv("GROQ_API_KEY"));
            case OPENAI -> LlmBuilder.openai(System.getenv("OPENAI_API_KEY"));
            case CLAUDE -> LlmBuilder.claude(System.getenv("CLAUDE_API_KEY"));
        };
            
            Inference inference = switch (strategy) {
                case SEQUENTIAL -> InferenceFactory.createSequential(llmService, mcpService, mcpServers);
                case REACT -> InferenceFactory.createReAct(llmService, mcpService, mcpServers, 
                                 Map.of("maxIterations", 5, "debug", true));
                case TOOLUSE -> InferenceFactory.createToolUse(llmService, mcpService, mcpServers,
                                 Map.of("debug", true));
            };
            
            return create()
                    .llm(llmService)
                    .inference(inference)
                    .memory(new InMemoryConversationMemory())
                    .build();
                    
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar configuração atual: " + e.getMessage(), e);
        }
    }
    
    public static ChatEngine reactSetup(LlmProvider provider) {
        return currentSetup(provider, InferenceStrategy.REACT);
    }
    
    public static ChatEngine reactSetup(LlmProvider provider, int maxIterations) {
        try {
            com.gazapps.mcp.MCPService mcpService = new com.gazapps.mcp.MCPService();
            com.gazapps.mcp.MCPServers mcpServers = new com.gazapps.mcp.MCPServers(mcpService);
            
            mcpServers.loadServers();
            mcpServers.processServerDependencies();
            mcpServers.connectToServers();
            
            Llm llmService = switch (provider) {
                case GEMINI -> LlmBuilder.gemini(System.getenv("GEMINI_API_KEY"));
                case GROQ -> LlmBuilder.groq(System.getenv("GROQ_API_KEY"));
                case OPENAI -> LlmBuilder.openai(System.getenv("OPENAI_API_KEY"));
                case CLAUDE -> LlmBuilder.claude(System.getenv("CLAUDE_API_KEY"));
            };
            
            Inference inference = InferenceFactory.createReAct(llmService, mcpService, mcpServers, maxIterations);
            
            return create()
                    .llm(llmService)
                    .inference(inference)
                    .memory(new InMemoryConversationMemory())
                    .build();
                    
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar configuração ReAct: " + e.getMessage(), e);
        }
    }
    
   
    @Override
    public String toString() {
        return String.format("ChatEngineBuilder{llmService=%s, inference=%s, memory=%s}",
                llmService != null ? llmService.getProviderName() : "null",
                inference != null ? inference.getClass().getSimpleName() : "null",
                memory != null ? memory.getClass().getSimpleName() : "default");
    }
}
