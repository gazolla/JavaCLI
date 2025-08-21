package com.gazapps.llm.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Representa o resultado de uma chamada de ferramenta executada por um LLM.
 * Esta classe padroniza a representação independentemente do provedor LLM.
 */
public final class ToolCall {
    
    private final String id;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final String result;
    private final boolean success;
    
    public ToolCall(String id, String toolName, Map<String, Object> arguments, String result, boolean success) {
        this.id = Objects.requireNonNull(id, "Tool call ID cannot be null");
        this.toolName = Objects.requireNonNull(toolName, "Tool name cannot be null");
        this.arguments = arguments != null ? Map.copyOf(arguments) : Collections.emptyMap();
        this.result = result;
        this.success = success;
    }
    
    /**
     * Cria uma chamada de ferramenta bem-sucedida.
     */
    public static ToolCall success(String id, String toolName, Map<String, Object> arguments, String result) {
        return new ToolCall(id, toolName, arguments, result, true);
    }
    
    /**
     * Cria uma chamada de ferramenta que falhou.
     */
    public static ToolCall failure(String id, String toolName, Map<String, Object> arguments, String errorMessage) {
        return new ToolCall(id, toolName, arguments, errorMessage, false);
    }
    
    /**
     * Cria uma chamada de ferramenta pendente (ainda não executada).
     */
    public static ToolCall pending(String id, String toolName, Map<String, Object> arguments) {
        return new ToolCall(id, toolName, arguments, null, true);
    }
    
    // Getters
    
    public String getId() {
        return id;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public Map<String, Object> getArguments() {
        return arguments;
    }
    
    public String getResult() {
        return result;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public boolean hasResult() {
        return result != null;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ToolCall toolCall = (ToolCall) obj;
        return success == toolCall.success &&
               Objects.equals(id, toolCall.id) &&
               Objects.equals(toolName, toolCall.toolName) &&
               Objects.equals(arguments, toolCall.arguments) &&
               Objects.equals(result, toolCall.result);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, toolName, arguments, result, success);
    }
    
    @Override
    public String toString() {
        return String.format("ToolCall{id='%s', toolName='%s', success=%s, hasResult=%s}",
                           id, toolName, success, hasResult());
    }
}
