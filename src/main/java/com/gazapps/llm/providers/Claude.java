package com.gazapps.llm.providers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.core.ChatEngineBuilder.LlmProvider;
import com.gazapps.llm.Llm;
import com.gazapps.llm.LlmCapabilities;
import com.gazapps.llm.LlmException;
import com.gazapps.llm.LlmResponse;
import com.gazapps.llm.tool.ToolCall;
import com.gazapps.llm.tool.ToolDefinition;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Implementação da interface Llm para Anthropic Claude.
 * Converte todas as estruturas específicas do Claude para os tipos padronizados.
 */
public class Claude implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Claude.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("claude");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;
    private LlmCapabilities capabilities;

    public Claude() {
        Config config = new Config();
        config.createConfigFileIfNeeded();
        
        Map<String, String> claudeConfig = config.getClaudeConfig();
        
        this.baseUrl = claudeConfig.get("baseUrl");
        this.apiKey = claudeConfig.get("apiKey");
        this.model = claudeConfig.get("model");
        this.timeout = Integer.parseInt(claudeConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(claudeConfig.get("debug"));
        
        // Definir capacidades do Claude
        this.capabilities = LlmCapabilities.builder()
            .functionCalling(true)
            .systemMessages(true)
            .streaming(false)
            .maxTokens(100000)
            .supportedFormats(java.util.Set.of("text", "json"))
            .build();
        
        config.isLlmConfigValid(LlmProvider.CLAUDE);
        
        if (debug) {
            logger.debug("🔧 Claude initialized with capabilities: {}", capabilities);
        }
    }

    @Override
    public LlmResponse generateResponse(String prompt) {
        return generateWithTools(prompt, null);
    }

    @Override
    public LlmResponse generateWithTools(String prompt, List<ToolDefinition> tools) {
        try {
            validateInput(prompt);
            
            // === LOGGING REQUEST ===
            if (conversationLogger.isInfoEnabled()) {
                conversationLogger.info("=== CLAUDE REQUEST ===");
                conversationLogger.info("Model: {}", model);
                conversationLogger.info("Prompt: {}", prompt);
                if (tools != null && !tools.isEmpty()) {
                    conversationLogger.info("Tools: {} tool(s) available", tools.size());
                    tools.forEach(tool -> 
                        conversationLogger.info("  - {}: {}", tool.getName(), tool.getDescription())
                    );
                }
            }
            
            HttpRequest request = buildRequest(prompt, tools);
            HttpResponse<String> response = sendRequest(request);
            LlmResponse llmResponse = parseResponse(response);
            
            // === LOGGING RESPONSE ===
            if (conversationLogger.isInfoEnabled()) {
                conversationLogger.info("=== CLAUDE RESPONSE ===");
                conversationLogger.info("Success: {}", llmResponse.isSuccess());
                if (llmResponse.isSuccess()) {
                    conversationLogger.info("Content: {}", llmResponse.getContent());
                    if (llmResponse.hasToolCalls()) {
                        conversationLogger.info("Tool calls: {}", llmResponse.getToolCalls().size());
                        llmResponse.getToolCalls().forEach(toolCall ->
                            conversationLogger.info("  - {}: {}", toolCall.getToolName(), toolCall.getArguments())
                        );
                    }
                } else {
                    conversationLogger.info("Error: {}", llmResponse.getErrorMessage());
                }
                conversationLogger.info("=== END CLAUDE ===");
            }
            
            return llmResponse;
            
        } catch (Exception e) {
            // === LOGGING ERROR ===
            if (conversationLogger.isErrorEnabled()) {
                conversationLogger.error("=== CLAUDE ERROR ===");
                conversationLogger.error("Error: {}", e.getMessage());
                conversationLogger.error("=== END CLAUDE ERROR ===");
            }
            
            LlmException.ErrorType errorType = determineErrorType(e);
            throw new LlmException(getProviderName(), errorType, e.getMessage(), e);
        }
    }

    @Override
    public List<ToolDefinition> convertMcpTools(List<Tool> mcpTools) {
        if (mcpTools == null || mcpTools.isEmpty()) {
            return List.of();
        }
        
        return mcpTools.stream()
            .map(ToolDefinition::fromMcp)
            .toList();
    }

    @Override
    public LlmProvider getProviderName() {
        return LlmProvider.CLAUDE;
    }

    @Override
    public LlmCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public boolean isHealthy() {
        try {
            LlmResponse response = generateResponse("Test");
            return response.isSuccess();
        } catch (Exception e) {
            logger.warn("Health check failed for Claude: {}", e.getMessage());
            return false;
        }
    }
    
    private void validateInput(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new LlmException(getProviderName(), LlmException.ErrorType.INVALID_REQUEST, 
                                 "Prompt cannot be empty");
        }
        
        if (prompt.length() > capabilities.getMaxTokens()) {
            throw new LlmException(getProviderName(), LlmException.ErrorType.INVALID_REQUEST, 
                                 "Prompt too long for Claude (maximum " + capabilities.getMaxTokens() + " characters)");
        }
    }
    
    private HttpRequest buildRequest(String prompt, List<com.gazapps.llm.tool.ToolDefinition> tools) throws Exception {
        ClaudeRequest requestObject = new ClaudeRequest(prompt, tools, model);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }
    
    private HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (debug) {
            logger.debug("Claude response status: {}", response.statusCode());
            logger.debug("Claude response body: {}", response.body());
        }
        
        if (response.statusCode() >= 400) {
            String errorMessage = "Claude API error: " + response.body();
            throw new IOException(errorMessage);
        }
        
        return response;
    }
    
    private LlmResponse parseResponse(HttpResponse<String> response) throws Exception {
        ClaudeResponse claudeResponse = objectMapper.readValue(response.body(), ClaudeResponse.class);
        
        if (claudeResponse.content == null || claudeResponse.content.length == 0) {
            return LlmResponse.error("No response from Claude");
        }
        
        // Claude pode retornar múltiplos content blocks
        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        
        for (ClaudeResponse.Content content : claudeResponse.content) {
            if ("text".equals(content.type) && content.text != null) {
                textContent.append(content.text);
            } else if ("tool_use".equals(content.type) && content.name != null) {
                String callId = content.id != null ? content.id : UUID.randomUUID().toString();
                ToolCall toolCall = ToolCall.pending(callId, content.name, content.input);
                toolCalls.add(toolCall);
            }
        }
        
        if (!toolCalls.isEmpty()) {
            return LlmResponse.withTools(textContent.toString(), toolCalls);
        } else {
            return LlmResponse.success(textContent.toString());
        }
    }
    
    private LlmException.ErrorType determineErrorType(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return LlmException.ErrorType.TIMEOUT;
        } else if (e instanceof java.net.ConnectException) {
            return LlmException.ErrorType.COMMUNICATION;
        } else if (e instanceof IOException && e.getMessage().contains("429")) {
            return LlmException.ErrorType.RATE_LIMIT;
        } else if (e instanceof IOException && e.getMessage().contains("401")) {
            return LlmException.ErrorType.AUTHENTICATION;
        } else if (e instanceof IOException) {
            return LlmException.ErrorType.COMMUNICATION;
        } else {
            return LlmException.ErrorType.UNKNOWN;
        }
    }
    
    // Classes internas para request/response do Claude
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ClaudeRequest {
        public String model;
        public int maxTokens = 4096;
        public Message[] messages;
        public ToolDefinition[] tools;

        public ClaudeRequest(String prompt, List<com.gazapps.llm.tool.ToolDefinition> toolDefinitions, String model) {
            this.model = model;
            this.messages = new Message[]{new Message("user", prompt)};
            this.tools = (toolDefinitions != null && !toolDefinitions.isEmpty()) ? 
                         toolDefinitions.stream()
                             .map(tool -> new ToolDefinition(tool.toClaudeFormat()))
                             .toArray(ToolDefinition[]::new) : null;
        }

        private static class Message {
            public String role;
            public String content;
            
            public Message(String role, String content) {
                this.role = role;
                this.content = content;
            }
        }
        
        private static class ToolDefinition {
            public String name;
            public String description;
            public Map<String, Object> inputSchema;
            
            public ToolDefinition(Map<String, Object> claudeFormat) {
                this.name = (String) claudeFormat.get("name");
                this.description = (String) claudeFormat.get("description");
                this.inputSchema = (Map<String, Object>) claudeFormat.get("input_schema");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ClaudeResponse {
        public String id;
        public String type;
        public String role;
        public String model;
        public Content[] content;
        public String stopReason;
        public Usage usage;

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Content {
            public String type;
            public String text;
            public String id;
            public String name;
            public Map<String, Object> input;
        }
        
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Usage {
            public int inputTokens;
            public int outputTokens;
        }
    }
}
