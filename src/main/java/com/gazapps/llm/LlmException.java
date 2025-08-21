package com.gazapps.llm;

import com.gazapps.core.ChatEngineBuilder.LlmProvider;

/**
 * Exceção base para todos os erros relacionados a LLMs.
 * Padroniza o tratamento de erros independentemente do provedor.
 */
public class LlmException extends RuntimeException {
    
    private final LlmProvider provider;
    private final ErrorType errorType;
    
    public enum ErrorType {
        COMMUNICATION("Erro de comunicação com o LLM"),
        RATE_LIMIT("Limite de taxa atingido"),
        TIMEOUT("Timeout na requisição"),
        INVALID_REQUEST("Requisição inválida"),
        TOOL_ERROR("Erro na execução de ferramenta"),
        AUTHENTICATION("Erro de autenticação"),
        UNKNOWN("Erro desconhecido");
        
        private final String description;
        
        ErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public LlmException(LlmProvider provider, ErrorType errorType, String message) {
        super(message);
        this.provider = provider;
        this.errorType = errorType;
    }
    
    public LlmException(LlmProvider provider, ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.errorType = errorType;
    }
    
    public LlmProvider getProvider() {
        return provider;
    }
    
    public ErrorType getErrorType() {
        return errorType;
    }
    
    @Override
    public String toString() {
        return String.format("LlmException{provider='%s', type=%s, message='%s'}", 
                           provider, errorType, getMessage());
    }
}
