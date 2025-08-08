package com.gazapps.llm;

import java.util.List;
import java.util.Map;

import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.exceptions.LLMException;
import com.gazapps.exceptions.InputException;

public interface Llm {
    
    String generateResponse(String prompt, List<FunctionDeclaration> functions);
    
    String getProviderName();
    
    boolean supportsToolCalling();
    
    void configure(Map<String, String> configuration);
    
    FunctionDeclaration convertMcpToolToFunction(io.modelcontextprotocol.spec.McpSchema.Tool mcpTool, String namespacedName); 

}
