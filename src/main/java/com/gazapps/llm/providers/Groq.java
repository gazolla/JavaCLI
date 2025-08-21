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
 * Implementação da interface Llm para Groq.
 * Converte todas as estruturas específicas do Groq para os tipos padronizados.
 */
public class Groq implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Groq.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("groq");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;
    private LlmCapabilities capabilities;

    public Groq() {
        Config config = new Config();
        config.createConfigFileIfNeeded();
        
        Map<String, String> groqConfig = config.getGroqConfig();
        
        this.baseUrl = groqConfig.get("baseUrl");
        this.apiKey = groqConfig.get("apiKey");
        this.model = groqConfig.get("model");
        this.timeout = Integer.parseInt(groqConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(groqConfig.get("debug"));
        
        // Definir capacidades do Groq
        this.capabilities = LlmCapabilities.builder()
            .functionCalling(true)
            .systemMessages(true)
            .streaming(false)
            .maxTokens(50000)
            .supportedFormats(java.util.Set.of("text", "json"))
            .build();
        
        config.isLlmConfigValid(LlmProvider.GROQ);
        
        if (debug) {
            logger.debug("🔧 Groq initialized with capabilities: {}", capabilities);
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
            
            HttpRequest request = buildRequest(prompt, tools);
            HttpResponse<String> response = sendRequest(request);
            
            return parseResponse(response);
            
        } catch (Exception e) {
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
        return LlmProvider.GROQ;
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
            logger.warn("Health check failed for Groq: {}", e.getMessage());
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
                                 "Prompt too long for Groq (maximum " + capabilities.getMaxTokens() + " characters)");
        }
    }
    
    private HttpRequest buildRequest(String prompt, List<com.gazapps.llm.tool.ToolDefinition> tools) throws Exception {
        GroqRequest requestObject = new GroqRequest(prompt, tools, model);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }
    
    private HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (debug) {
            logger.debug("Groq response status: {}", response.statusCode());
            logger.debug("Groq response body: {}", response.body());
        }
        
        if (response.statusCode() >= 400) {
            String errorMessage = "Groq API error: " + response.body();
            throw new IOException(errorMessage);
        }
        
        return response;
    }
    
    private LlmResponse parseResponse(HttpResponse<String> response) throws Exception {
        GroqResponse groqResponse = objectMapper.readValue(response.body(), GroqResponse.class);
        
        if (groqResponse.choices == null || groqResponse.choices.length == 0) {
            return LlmResponse.error("No response from Groq");
        }
        
        GroqResponse.Choice choice = groqResponse.choices[0];
        GroqResponse.Message message = choice.message;
        
        if (message.toolCalls != null && message.toolCalls.length > 0) {
            // Resposta com tool calls
            List<ToolCall> toolCalls = java.util.Arrays.stream(message.toolCalls)
                .map(tc -> ToolCall.pending(tc.id, tc.function.name, tc.function.arguments))
                .toList();
            return LlmResponse.withTools(message.content, toolCalls);
        } else if (message.content != null) {
            // Resposta de texto simples
            return LlmResponse.success(message.content);
        } else {
            return LlmResponse.error("Invalid response format from Groq");
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
    
    // Classes internas para request/response do Groq
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class GroqRequest {
        public String model;
        public Message[] messages;
        public ToolDefinition[] tools;

        public GroqRequest(String prompt, List<com.gazapps.llm.tool.ToolDefinition> toolDefinitions, String model) {
            this.model = model;
            this.messages = new Message[]{new Message("user", prompt)};
            this.tools = (toolDefinitions != null && !toolDefinitions.isEmpty()) ? 
                         toolDefinitions.stream()
                             .map(tool -> new ToolDefinition(tool.toGroqFormat()))
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
            public String type = "function";
            public Function function;
            
            public ToolDefinition(Map<String, Object> groqFormat) {
                this.function = new Function((Map<String, Object>) groqFormat.get("function"));
            }
        }
        
        private static class Function {
            public String name;
            public String description;
            public Map<String, Object> parameters;
            
            public Function(Map<String, Object> functionData) {
                this.name = (String) functionData.get("name");
                this.description = (String) functionData.get("description");
                this.parameters = (Map<String, Object>) functionData.get("parameters");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GroqResponse {
        public Choice[] choices;
        public Usage usage;

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Choice {
            public int index;
            public Message message;
            public String finishReason;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Message {
            public String role;
            public String content;
            public ToolCall[] toolCalls;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class ToolCall {
            public String id;
            public String type;
            public Function function;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Function {
            public String name;
            public Map<String, Object> arguments;
        }
        
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Usage {
            public int promptTokens;
            public int completionTokens;
            public int totalTokens;
        }
    }
}
