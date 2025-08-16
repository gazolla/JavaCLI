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
        CLAUDE
    }
    
    public enum InferenceStrategy {
        SEQUENTIAL,
        REACT,
        TOOLUSE
    }
    

    private ChatEngineBuilder() {
        try {
            this.mcpService = new com.gazapps.mcp.MCPService();
            this.mcpServers = new com.gazapps.mcp.MCPServers(this.mcpService);
            
            this.mcpServers.loadServers();
            System.out.println("Load Servers.");
            this.mcpServers.processServerDependencies();
            System.out.println("process Server Dependencies.");
            this.mcpServers.connectToServers();
            System.out.println("connect To Servers.");
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
        
        return new ChatEngine(llmService, inference, memory, mcpServers);
    }
    

    private static ChatEngine setup(LlmProvider provider, InferenceStrategy strategy, 
                                 Map<String, Object> inferenceParams) {
        ChatEngineBuilder builder = create();
        
        try {
            Llm llmService = switch (provider) {
                case GEMINI -> LlmBuilder.gemini(null);
                case GROQ -> LlmBuilder.groq(null);
                case OPENAI -> LlmBuilder.openai(null);
                case CLAUDE -> LlmBuilder.claude(null);
            };
            
            Inference inference = createInference(strategy, llmService, 
                                                 builder.mcpService, builder.mcpServers, 
                                                 inferenceParams);
            
            return builder
                    .llm(llmService)
                    .inference(inference)
                    .build();
                    
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar configuração: " + e.getMessage(), e);
        }
    }
    
    private static Inference createInference(InferenceStrategy strategy, Llm llmService, 
                                           com.gazapps.mcp.MCPService mcpService, 
                                           com.gazapps.mcp.MCPServers mcpServers,
                                           Map<String, Object> params) {
        return switch (strategy) {
            case SEQUENTIAL -> InferenceFactory.createSequential(llmService, mcpService, mcpServers);
            case REACT -> InferenceFactory.createReAct(llmService, mcpService, mcpServers, 
                             params != null ? params : Map.of("maxIterations", 5, "debug", true));
            case TOOLUSE -> InferenceFactory.createToolUse(llmService, mcpService, mcpServers,
                             params != null ? params : Map.of("debug", true));
        };
    }
    

    public static ChatEngine currentSetup(LlmProvider provider) {
        return currentSetup(provider, InferenceStrategy.SEQUENTIAL);
    }
    
    public static ChatEngine currentSetup(LlmProvider provider, InferenceStrategy strategy) {
        return setup(provider, strategy, null);
    }
    
    public static ChatEngine toolUseSetup(LlmProvider provider) {
        return setup(provider, InferenceStrategy.TOOLUSE, null);
    }
    
    public static ChatEngine toolUseChainedSetup(LlmProvider provider, int maxChainLength) {
        return setup(provider, InferenceStrategy.TOOLUSE, 
                   Map.of("maxChainLength", maxChainLength, "debug", true));
    }
    
    public static ChatEngine reactSetup(LlmProvider provider) {
        return setup(provider, InferenceStrategy.REACT, null);
    }
    
    public static ChatEngine reactSetup(LlmProvider provider, int maxIterations) {
        return setup(provider, InferenceStrategy.REACT, 
                   Map.of("maxIterations", maxIterations, "debug", true));
    }
    
    @Override
    public String toString() {
        return String.format("ChatEngineBuilder{llmService=%s, inference=%s, memory=%s}",
                llmService != null ? llmService.getProviderName() : "null",
                inference != null ? inference.getClass().getSimpleName() : "null",
                memory != null ? memory.getClass().getSimpleName() : "default");
    }
}