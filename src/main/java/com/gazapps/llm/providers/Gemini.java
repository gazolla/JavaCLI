package com.gazapps.llm.providers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Implementação da interface Llm para o Google Gemini.
 * Converte todas as estruturas específicas do Gemini para os tipos padronizados.
 */
public class Gemini implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Gemini.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("gemini");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;
    private LlmCapabilities capabilities;

    public Gemini() {
        Config config = new Config();
        config.createConfigFileIfNeeded();
        
        Map<String, String> geminiConfig = config.getGeminiConfig();
        
        this.baseUrl = geminiConfig.get("baseUrl");
        this.apiKey = geminiConfig.get("apiKey");
        this.model = geminiConfig.get("model");
        this.timeout = Integer.parseInt(geminiConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(geminiConfig.get("debug"));
        
        // Definir capacidades do Gemini
        this.capabilities = LlmCapabilities.builder()
            .functionCalling(true)
            .systemMessages(true)
            .streaming(false)
            .maxTokens(32000)
            .supportedFormats(java.util.Set.of("text", "json"))
            .build();
        
        config.isLlmConfigValid(LlmProvider.GEMINI);
        
        if (debug) {
            logger.debug("🔧 Gemini initialized with capabilities: {}", capabilities);
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
                conversationLogger.info("=== GEMINI REQUEST ===");
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
                conversationLogger.info("=== GEMINI RESPONSE ===");
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
                conversationLogger.info("=== END GEMINI ===");
            }
            
            return llmResponse;
            
        } catch (Exception e) {
            // === LOGGING ERROR ===
            if (conversationLogger.isErrorEnabled()) {
                conversationLogger.error("=== GEMINI ERROR ===");
                conversationLogger.error("Error: {}", e.getMessage());
                conversationLogger.error("=== END GEMINI ERROR ===");
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
        return LlmProvider.GEMINI;
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
            logger.warn("Health check failed for Gemini: {}", e.getMessage());
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
                                 "Prompt too long for Gemini (maximum " + capabilities.getMaxTokens() + " characters)");
        }
    }
    
    private HttpRequest buildRequest(String prompt, List<ToolDefinition> tools) throws Exception {
        GeminiRequest requestObject = new GeminiRequest(prompt, tools);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }
    
    private HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (debug) {
            logger.debug("Gemini response status: {}", response.statusCode());
            logger.debug("Gemini response body: {}", response.body());
        }
        
        if (response.statusCode() >= 400) {
            String errorMessage = "Gemini API error: " + response.body();
            throw new IOException(errorMessage);
        }
        
        return response;
    }
    
    private LlmResponse parseResponse(HttpResponse<String> response) throws Exception {
        GeminiResponse geminiResponse = objectMapper.readValue(response.body(), GeminiResponse.class);
        
        if (geminiResponse.candidates == null || geminiResponse.candidates.length == 0) {
            return LlmResponse.error("No response from Gemini");
        }
        
        GeminiResponse.Candidate candidate = geminiResponse.candidates[0];
        if (candidate.content == null || candidate.content.parts == null || candidate.content.parts.length == 0) {
            return LlmResponse.error("Empty response from Gemini");
        }
        
        GeminiResponse.Part part = candidate.content.parts[0];
        
        if (part.functionCall != null) {
            // Resposta com function call
            String callId = UUID.randomUUID().toString();
            ToolCall toolCall = ToolCall.pending(callId, part.functionCall.name, part.functionCall.args);
            return LlmResponse.withTools("", List.of(toolCall));
        } else if (part.text != null) {
            // Resposta de texto simples
            return LlmResponse.success(part.text);
        } else {
            return LlmResponse.error("Invalid response format from Gemini");
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
    
    // Classes internas para request/response do Gemini
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class GeminiRequest {
        public Content[] contents;
        public Tools tools;

        public GeminiRequest(String prompt, List<ToolDefinition> toolDefinitions) {
            this.contents = new Content[]{new Content(prompt)};
            this.tools = (toolDefinitions != null && !toolDefinitions.isEmpty()) ? 
                         new Tools(toolDefinitions) : null;
        }

        private static class Content {
            public String role = "user";
            public Part[] parts;
            
            public Content(String prompt) {
                this.parts = new Part[]{new Part(prompt)};
            }
        }

        private static class Part {
            public String text;
            
            public Part(String text) {
                this.text = text;
            }
        }

        private static class Tools {
            public FunctionDeclaration[] functionDeclarations;
            
            public Tools(List<ToolDefinition> toolDefinitions) {
                this.functionDeclarations = toolDefinitions.stream()
                    .map(tool -> new FunctionDeclaration(tool.toGeminiFormat()))
                    .toArray(FunctionDeclaration[]::new);
            }
        }
        
        private static class FunctionDeclaration {
            public String name;
            public String description;
            public Map<String, Object> parameters;
            
            public FunctionDeclaration(Map<String, Object> geminiFormat) {
                this.name = (String) geminiFormat.get("name");
                this.description = (String) geminiFormat.get("description");
                this.parameters = (Map<String, Object>) geminiFormat.get("parameters");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeminiResponse {
        public Candidate[] candidates;

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Candidate {
            public Content content;
            public String finishReason;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Content {
            public Part[] parts;
            public String role;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Part {
            public String text;
            public FunctionCall functionCall;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class FunctionCall {
            public String name;
            public Map<String, Object> args;
        }
    }
}
