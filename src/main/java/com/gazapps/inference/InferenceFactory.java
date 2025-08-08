package com.gazapps.inference;

import java.util.Map;

import com.gazapps.inference.bigprompt.SimpleSequential;
import com.gazapps.inference.react.ReActInference;
import com.gazapps.inference.tooluse.ToolUseInference;
import com.gazapps.llm.Llm;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

public final class InferenceFactory {
    
    public static Inference createSequential(Llm llmService, MCPService mcpService, MCPServers mcpServers) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        SimpleSequential inference = new SimpleSequential(llmService, mcpService, mcpServers);
        return inference;
    }
    
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
    
    public static Inference createToolUse(Llm llmService, MCPService mcpService, MCPServers mcpServers, 
                                          Map<String, Object> options) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ToolUseInference(llmService, mcpService, mcpServers, options);
    }
    
    public static Inference createToolUseChained(Llm llmService, MCPService mcpService, MCPServers mcpServers, 
                                                 int maxChainLength) {
        if (llmService == null) {
            throw new IllegalArgumentException("LLMService é obrigatório");
        }
        
        if (mcpService == null || mcpServers == null) {
            throw new IllegalArgumentException("MCPService e MCPServers são obrigatórios");
        }
        
        return new ToolUseInference(llmService, mcpService, mcpServers, 
                                   Map.of("debug", true, "enableToolChaining", true, "maxToolChainLength", maxChainLength));
    }
}
