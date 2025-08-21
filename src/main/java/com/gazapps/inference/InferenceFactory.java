package com.gazapps.inference;

import java.util.HashMap;
import java.util.Map;

import com.gazapps.inference.react.ReActInference;
import com.gazapps.inference.reflection.ReflectionInference;
import com.gazapps.inference.simple.SimpleInference;
import com.gazapps.llm.Llm;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;
import com.gazapps.core.ChatEngineBuilder.InferenceStrategy;


public final class InferenceFactory {
    
    
    public static Inference createReAct(Llm llmService, MCPService mcpService, MCPServers mcpServers, 
                                        int maxIterations) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ReActInference(llmService, mcpService, mcpServers, 
                                 Map.of("maxIterations", maxIterations, "debug", false));
    }
    
    public static Inference createReAct(Llm llmService, MCPService mcpService, MCPServers mcpServers, 
                                        Map<String, Object> options) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ReActInference(llmService, mcpService, mcpServers, options);
    }
    
    public static Inference createReflection(Llm llmService, MCPService mcpService, MCPServers mcpServers,
                                            int maxIterations, boolean debug) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ReflectionInference(llmService, mcpService, mcpServers, maxIterations, debug);
    }
    
    public static Inference createReflection(Llm llmService, MCPService mcpService, MCPServers mcpServers,
                                            Map<String, Object> params) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ReflectionInference(llmService, mcpService, mcpServers, params);
    }
    
    public static Inference createSimple(Llm llmService, MCPService mcpService, MCPServers mcpServers) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new SimpleInference(llmService, mcpService, mcpServers, new HashMap<>());
    }
    
    public static Inference createSimple(Llm llmService, MCPService mcpService, MCPServers mcpServers,
                                         Map<String, Object> options) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new SimpleInference(llmService, mcpService, mcpServers, options);
    }

    public static Inference createInference(InferenceStrategy strategy, Llm llmService, MCPService mcpService, MCPServers mcpServers,
			Map<String, Object> defaultOptions) {
		return switch (strategy) {
		    case REACT -> createReAct(llmService, mcpService, mcpServers, defaultOptions);
            case REFLECTION -> createReflection(llmService, mcpService, mcpServers, defaultOptions);
            case SIMPLE -> createSimple(llmService, mcpService, mcpServers, defaultOptions);
            default -> throw new IllegalArgumentException("Inference strategy '" + strategy + "' não encontrada.");
		};
	}
}
