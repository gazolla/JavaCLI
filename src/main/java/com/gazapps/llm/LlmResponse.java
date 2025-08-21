package com.gazapps.llm;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.gazapps.llm.tool.ToolCall;

/**
 * Resposta padronizada de um LLM que encapsula tanto conteúdo textual
 * quanto possíveis chamadas de ferramentas executadas.
 * Esta classe é imutável para garantir thread-safety.
 */
public final class LlmResponse {
    
    private final String content;
    private final boolean success;
    private final List<ToolCall> toolCalls;
    private final String errorMessage;
    private final Exception originalException;
    
    private LlmResponse(String content, boolean success, List<ToolCall> toolCalls, 
                       String errorMessage, Exception originalException) {
        this.content = content;
        this.success = success;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList();
        this.errorMessage = errorMessage;
        this.originalException = originalException;
    }
    
    /**
     * Cria uma resposta de sucesso apenas com conteúdo textual.
     */
    public static LlmResponse success(String content) {
        return new LlmResponse(
            Objects.requireNonNull(content, "Content cannot be null"), 
            true, 
            Collections.emptyList(), 
            null, 
            null
        );
    }
    
    /**
     * Cria uma resposta de sucesso com conteúdo e chamadas de ferramentas.
     */
    public static LlmResponse withTools(String content, List<ToolCall> toolCalls) {
        return new LlmResponse(
            content, 
            true, 
            toolCalls, 
            null, 
            null
        );
    }
    
    /**
     * Cria uma resposta de erro com mensagem.
     */
    public static LlmResponse error(String errorMessage) {
        return new LlmResponse(
            null, 
            false, 
            Collections.emptyList(), 
            Objects.requireNonNull(errorMessage, "Error message cannot be null"), 
            null
        );
    }
    
    /**
     * Cria uma resposta de erro com mensagem e exceção original.
     */
    public static LlmResponse error(String errorMessage, Exception originalException) {
        return new LlmResponse(
            null, 
            false, 
            Collections.emptyList(), 
            Objects.requireNonNull(errorMessage, "Error message cannot be null"), 
            originalException
        );
    }
    
    /**
     * Retorna o conteúdo textual da resposta.
     */
    public String getContent() {
        return content;
    }
    
    /**
     * Indica se a operação foi bem-sucedida.
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Retorna a lista de chamadas de ferramentas (imutável).
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    /**
     * Retorna true se existem chamadas de ferramentas na resposta.
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
    
    /**
     * Retorna a mensagem de erro se houver.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Retorna a exceção original se houver.
     */
    public Exception getOriginalException() {
        return originalException;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        LlmResponse that = (LlmResponse) obj;
        return success == that.success &&
               Objects.equals(content, that.content) &&
               Objects.equals(toolCalls, that.toolCalls) &&
               Objects.equals(errorMessage, that.errorMessage) &&
               Objects.equals(originalException, that.originalException);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(content, success, toolCalls, errorMessage, originalException);
    }
    
    @Override
    public String toString() {
        if (success) {
            return String.format("LlmResponse{success=true, content='%s', toolCalls=%d}", 
                                content != null ? (content.length() > 100 ? content.substring(0, 100) + "..." : content) : "null",
                                toolCalls.size());
        } else {
            return String.format("LlmResponse{success=false, error='%s'}", errorMessage);
        }
    }
}
