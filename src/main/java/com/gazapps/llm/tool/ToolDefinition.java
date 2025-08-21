package com.gazapps.llm.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Representação interna padronizada de uma ferramenta, independente
 * do formato específico do LLM de origem.
 * Esta classe centraliza toda a lógica de conversão entre formatos.
 */
public final class ToolDefinition {
    
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final List<String> required;
    
    public ToolDefinition(String name, String description, Map<String, Object> parameters, List<String> required) {
        this.name = Objects.requireNonNull(name, "Tool name cannot be null");
        this.description = Objects.requireNonNull(description, "Tool description cannot be null");
        this.parameters = parameters != null ? Map.copyOf(parameters) : Collections.emptyMap();
        this.required = required != null ? List.copyOf(required) : Collections.emptyList();
    }
    
    /**
     * Factory method para converter de ferramenta MCP.
     */
    public static ToolDefinition fromMcp(Tool mcpTool) {
        Objects.requireNonNull(mcpTool, "MCP tool cannot be null");
        
        Map<String, Object> parameters = Collections.emptyMap();
        List<String> required = Collections.emptyList();
        
        if (mcpTool.inputSchema() != null) {
            if (mcpTool.inputSchema().properties() != null) {
                parameters = new HashMap<>(mcpTool.inputSchema().properties());
            }
            if (mcpTool.inputSchema().required() != null) {
                required = List.copyOf(mcpTool.inputSchema().required());
            }
        }
        
        return new ToolDefinition(
            mcpTool.name(),
            mcpTool.description() != null ? mcpTool.description() : "",
            parameters,
            required
        );
    }
    
    /**
     * Converte para formato Gemini FunctionDeclaration.
     */
    public Map<String, Object> toGeminiFormat() {
        Map<String, Object> geminiTool = new HashMap<>();
        geminiTool.put("name", name);
        geminiTool.put("description", description);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", this.parameters);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }
        
        geminiTool.put("parameters", parameters);
        return geminiTool;
    }
    
    /**
     * Converte para formato Groq/OpenAI.
     */
    public Map<String, Object> toGroqFormat() {
        Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", description);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", this.parameters);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }
        
        function.put("parameters", parameters);
        
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        
        return tool;
    }
    
    /**
     * Converte para formato Claude.
     */
    public Map<String, Object> toClaudeFormat() {
        Map<String, Object> claudeTool = new HashMap<>();
        claudeTool.put("name", name);
        claudeTool.put("description", description);
        
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", this.parameters);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        
        claudeTool.put("input_schema", inputSchema);
        return claudeTool;
    }
    
    /**
     * Converte para formato OpenAI (mesmo que Groq).
     */
    public Map<String, Object> toOpenAiFormat() {
        return toGroqFormat();
    }
    
    // Getters
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public List<String> getRequired() {
        return required;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ToolDefinition that = (ToolDefinition) obj;
        return Objects.equals(name, that.name) &&
               Objects.equals(description, that.description) &&
               Objects.equals(parameters, that.parameters) &&
               Objects.equals(required, that.required);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, description, parameters, required);
    }
    
    @Override
    public String toString() {
        return String.format("ToolDefinition{name='%s', description='%s', parameters=%d, required=%d}",
                           name, description, parameters.size(), required.size());
    }
}
