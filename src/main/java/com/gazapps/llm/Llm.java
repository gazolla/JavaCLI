package com.gazapps.llm;

import java.util.List;

import com.gazapps.core.ChatEngineBuilder.LlmProvider;
import com.gazapps.llm.tool.ToolDefinition;
import com.gazapps.llm.tool.ToolCall;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Interface principal para todos os provedores de LLM.
 * Define o contrato base que qualquer implementação de LLM deve seguir,
 * permitindo que as inferências trabalhem de forma agnóstica ao provedor.
 */
public interface Llm {
    
    /**
     * Gera uma resposta de texto básica sem ferramentas.
     * 
     * @param prompt O prompt de entrada
     * @return Resposta estruturada do LLM
     */
    LlmResponse generateResponse(String prompt);
    
    /**
     * Gera uma resposta com suporte a function calling.
     * 
     * @param prompt O prompt de entrada
     * @param tools Lista de ferramentas disponíveis
     * @return Resposta estruturada do LLM incluindo possíveis chamadas de ferramentas
     */
    LlmResponse generateWithTools(String prompt, List<ToolDefinition> tools);
    
    /**
     * Converte ferramentas MCP para o formato padronizado interno.
     * 
     * @param mcpTools Lista de ferramentas MCP
     * @return Lista de ferramentas no formato padronizado
     */
    List<ToolDefinition> convertMcpTools(List<Tool> mcpTools);
    
    /**
     * Retorna o nome do provedor LLM.
     * 
     * @return String identificando o provedor (ex: "gemini", "openai", "claude")
     */
    LlmProvider getProviderName();
    
    /**
     * Retorna as capacidades específicas deste LLM.
     * 
     * @return Informações sobre o que este LLM suporta
     */
    LlmCapabilities getCapabilities();
    
    /**
     * Verifica se a conexão com o LLM está funcionando.
     * 
     * @return true se o LLM está acessível e funcionando
     */
    boolean isHealthy();
}
