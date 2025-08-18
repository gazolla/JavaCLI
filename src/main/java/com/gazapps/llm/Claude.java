package com.gazapps.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.exceptions.InputException;
import com.gazapps.exceptions.LLMException;
import com.gazapps.llm.function.FunctionCallResult;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.llm.function.FunctionParameters;
import com.gazapps.llm.function.ParameterDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.stream.Collectors;

public class Claude implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Claude.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;

    public Claude() {
        Config config = new Config();
        config.createConfigFileIfNeeded();
        Map<String, String> claudeConfig = config.getClaudeConfig();

        this.baseUrl = claudeConfig.get("baseUrl");
        this.apiKey = claudeConfig.get("apiKey");
        this.model = claudeConfig.get("model");
        this.timeout = Integer.parseInt(claudeConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(claudeConfig.get("debug"));

        config.isLlmConfigValid("claude");

        if (debug) {
            logger.debug("🔧 Claude Debug:");
            logger.debug("   URL: {}", baseUrl);
            logger.debug("   Modelo: {}", model);
            logger.debug("   Timeout: {}s", timeout);
            logger.debug("   API Key: {}", (apiKey.isEmpty() ? "❌ Não configurada" : "✅ Configurada"));
        }
    }

    @Override
    public String generateResponse(String prompt, List<FunctionDeclaration> functions) {
        try {
            if (prompt == null || prompt.trim().isEmpty()) {
                throw new InputException("Prompt cannot be empty");
            }

            if (prompt.length() > 150000) {
                throw new InputException("Prompt too long for Claude (max 150,000 characters)");
            }

            HttpRequest request = buildRequest(prompt, functions);
            HttpResponse<String> response = sendMessage(request);
            FunctionCallResult result = extractAnswer(response);

            if (result != null) {
                if (result.isFunctionCall()) {
                    return "FUNCTION_CALL:" + result.getFunctionName() + ":" + objectMapper.writeValueAsString(result.getArguments());
                } else {
                    return result.getText();
                }
            }
            return "";

        } catch (InputException e) {
            throw new RuntimeException(e);
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(new LLMException("Connection timeout", "Claude", e));
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(new LLMException("Could not connect to service", "Claude", e));
        } catch (java.io.IOException e) {
            throw new RuntimeException(new LLMException("Communication error", "Claude", e));
        } catch (Exception e) {
            throw new RuntimeException(new LLMException("Unexpected error: " + e.getMessage(), "Claude", e));
        }
    }

    @Override
    public String getProviderName() {
        return "Claude";
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public void configure(Map<String, String> configuration) {
        if (configuration.containsKey("apiKey")) {
            this.apiKey = configuration.get("apiKey");
        }
        if (configuration.containsKey("model")) {
            this.model = configuration.get("model");
        }
        if (configuration.containsKey("timeout")) {
            this.timeout = Integer.parseInt(configuration.get("timeout"));
        }
        if (configuration.containsKey("debug")) {
            this.debug = Boolean.parseBoolean(configuration.get("debug"));
        }
    }

    public HttpRequest buildRequest(String prompt, List<FunctionDeclaration> functions) throws Exception {
        ClaudeRequest requestObject = new ClaudeRequest(prompt, functions, model);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        if (debug) {
            logger.debug("🔍 REQUEST BODY (Claude):");
            logger.debug(requestBody);
        }

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey) // Claude usa um header diferente
                .header("anthropic-version", "2023-06-01") // Versão da API necessária
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    public HttpResponse<String> sendMessage(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (debug) {
            logger.debug("Status Code: {}", response.statusCode());
            logger.debug("JSON Response: {}", response.body());
        }
        return response;
    }

    public FunctionCallResult extractAnswer(HttpResponse<String> response) throws Exception {
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            throw new IOException("Claude API error: " + errorBody);
        }

        try {
            ClaudeResponse responseObject = objectMapper.readValue(response.body(), ClaudeResponse.class);
            return responseObject.parseResult();
        } catch (Exception e) {
            throw new IOException("Failed to process Claude API response: " + e.getMessage(), e);
        }
    }

    @Override
    public FunctionDeclaration convertMcpToolToFunction(io.modelcontextprotocol.spec.McpSchema.Tool mcpTool, String namespacedName) {
        String description = mcpTool.description() != null ? mcpTool.description() : "No description available";
        JsonNode inputSchemaNode = mcpTool.inputSchema() != null ? objectMapper.valueToTree(mcpTool.inputSchema()) : objectMapper.createObjectNode();

        Map<String, ParameterDetails> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        if (inputSchemaNode.has("properties")) {
            JsonNode propertiesNode = inputSchemaNode.get("properties");
            propertiesNode.fields().forEachRemaining(entry -> {
                String paramName = entry.getKey();
                JsonNode paramSchema = entry.getValue();
                String paramType = paramSchema.has("type") ? paramSchema.get("type").asText() : "string";
                String paramDescription = paramSchema.has("description") ? paramSchema.get("description").asText() : "No description";

                Map<String, Object> items = null;
                if ("array".equals(paramType) && paramSchema.has("items")) {
                    items = objectMapper.convertValue(paramSchema.get("items"), Map.class);
                }

                ParameterDetails paramDetails = new ParameterDetails(paramType, paramDescription, items);
                properties.put(paramName, paramDetails);
            });
        }

        if (inputSchemaNode.has("required")) {
            JsonNode requiredNode = inputSchemaNode.get("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }
        }

        FunctionParameters parameters = new FunctionParameters("object", properties, required);
        return new FunctionDeclaration(namespacedName, description, parameters);
    }

    // Classes auxiliares para serialização/desserialização JSON

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ClaudeRequest {
        public String model;
        public List<Message> messages;
        public List<Tool> tools;
        public String tool_choice = "auto";
        public int max_tokens = 4096;

        public ClaudeRequest(String prompt, List<FunctionDeclaration> functions, String model) {
            this.model = model;
            this.messages = new ArrayList<>();
            this.messages.add(new Message("user", new Content("text", prompt, null)));
            this.tools = functions != null && !functions.isEmpty() ? functions.stream().map(Tool::new).collect(Collectors.toList()) : null;
        }

        private static class Message {
            public String role;
            public List<Content> content;
            public Message(String role, Content... content) {
                this.role = role;
                this.content = new ArrayList<>();
                for (Content part : content) {
                    this.content.add(part);
                }
            }
        }

        private static class Content {
            public String type;
            public String text;
            public ToolUse tool_use;
            public Content(String type, String text, ToolUse tool_use) {
                this.type = type;
                this.text = text;
                this.tool_use = tool_use;
            }
        }

        private static class ToolUse {
            public String id; // Opcional, mas útil para o parser de resposta
            public String name;
            public Map<String, Object> input;
        }

        private static class Tool {
            public String name;
            public String description;
            public Map<String, Object> input_schema;
            public Tool(FunctionDeclaration funcDecl) {
                this.name = funcDecl.name;
                this.description = funcDecl.description;
                this.input_schema = convertToJsonSchema(funcDecl.parameters);
            }
            
            private Map<String, Object> convertToJsonSchema(FunctionParameters params) {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", params.type);
                if (params.properties != null && !params.properties.isEmpty()) {
                    Map<String, Object> properties = new HashMap<>();
                    for (Map.Entry<String, ParameterDetails> entry : params.properties.entrySet()) {
                        String propName = entry.getKey();
                        ParameterDetails details = entry.getValue();
                        Map<String, Object> propSchema = new HashMap<>();
                        propSchema.put("type", details.type);
                        if (details.description != null && !details.description.isEmpty()) {
                            propSchema.put("description", details.description);
                        }
                        if (details.items != null) {
                            propSchema.put("items", details.items);
                        }
                        properties.put(propName, propSchema);
                    }
                    schema.put("properties", properties);
                }
                
                if (params.required != null && params.required instanceof List) {
                    schema.put("required", params.required);
                }
                
                return schema;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ClaudeResponse {
        public String id;
        public String type;
        public String role;
        public List<Content> content;
        public String stop_reason;
        public String model;
        public Error error;

        public FunctionCallResult parseResult() {
            if (error != null) {
                return new FunctionCallResult("Claude API error: " + error.message);
            }

            if (this.content != null && !this.content.isEmpty()) {
                for (Content part : this.content) {
                    if ("tool_use".equals(part.type) && part.tool_use != null) {
                        return new FunctionCallResult(part.tool_use.name, part.tool_use.input);
                    } else if ("text".equals(part.type) && part.text != null) {
                        return new FunctionCallResult(part.text);
                    }
                }
            }
            return null;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Content {
            public String type;
            public String text;
            public ToolUse tool_use;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class ToolUse {
            public String name;
            public Map<String, Object> input;
        }
        
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Error {
            public String type;
            public String message;
        }
    }
}