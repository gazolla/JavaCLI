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

public class OpenAi implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(OpenAi.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("openai");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;

    public OpenAi() {
        Config config = new Config();
        config.createConfigFileIfNeeded();
        Map<String, String> openAiConfig = config.getOpenAiConfig();

        this.baseUrl = openAiConfig.get("baseUrl");
        this.apiKey = openAiConfig.get("apiKey");
        this.model = openAiConfig.get("model");
        this.timeout = Integer.parseInt(openAiConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(openAiConfig.get("debug"));

        config.isLlmConfigValid("openai");

        if (debug) {
            logger.debug("🔧 OpenAI Debug:");
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
            
            // OpenAI context limit is usually model-specific, e.g., 128k for gpt-4-turbo
            // A simple char length check can be a starting point, but token counting is better.
            if (prompt.length() > 100000) {
                throw new InputException("Prompt too long for OpenAI (max 100,000 characters)");
            }

            // Log da interação limpa
            String cleanInput = extractCorePrompt(prompt);

            FunctionDeclaration[] tools = functions != null ? functions.toArray(new FunctionDeclaration[0]) : null;
            HttpRequest request = buildRequest(prompt, tools);
            HttpResponse<String> response = sendMessage(request);
            FunctionCallResult result = extractAnswer(response);

            String cleanOutput;
            String finalResult;
            
            if (result != null) {
                if (result.isFunctionCall()) {
                    cleanOutput = "[FUNCTION_CALL]";
                    finalResult = "FUNCTION_CALL:" + result.getFunctionName() + ":" + objectMapper.writeValueAsString(result.getArguments());
                } else {
                    cleanOutput = extractCoreResponse(result.getText());
                    finalResult = result.getText();
                }
            } else {
                cleanOutput = "[NO_RESPONSE]";
                finalResult = "";
            }
            
            // Log da conversação limpa
            conversationLogger.info("=== OPENAI INTERACTION ===");
            conversationLogger.info("INPUT: {}", cleanInput);
            conversationLogger.info("OUTPUT: {}", cleanOutput);
            conversationLogger.info(""); // linha em branco
            
            return finalResult;

        } catch (InputException e) {
            throw new RuntimeException(e);
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(new LLMException("Connection timeout", "OpenAI", e));
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(new LLMException("Could not connect to service", "OpenAI", e));
        } catch (java.io.IOException e) {
            throw new RuntimeException(new LLMException("Communication error", "OpenAI", e));
        } catch (Exception e) {
            throw new RuntimeException(new LLMException("Unexpected error: " + e.getMessage(), "OpenAI", e));
        }
    }

    @Override
    public String getProviderName() {
        return "OpenAI";
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

    public HttpRequest buildRequest(String prompt, FunctionDeclaration[] functions) throws Exception {
        OpenAiRequestWithTools requestObject = new OpenAiRequestWithTools(prompt, functions, model);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        if (debug) {
            logger.debug("🔍 REQUEST BODY (OpenAI):");
            logger.debug(requestBody);
        }

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
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
            throw new IOException("OpenAI API error: " + errorBody);
        }

        try {
            OpenAiResponseWithFunctions responseObject = objectMapper.readValue(response.body(), OpenAiResponseWithFunctions.class);
            return responseObject.parseResult();
        } catch (Exception e) {
            throw new IOException("Failed to process OpenAI API response: " + e.getMessage(), e);
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
    
    /**
     * Extrai o núcleo do prompt removendo instruções técnicas
     */
    private String extractCorePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "[EMPTY_PROMPT]";
        }
        
        // Se é muito longo e contém instruções de sistema, extrair query real
        if (prompt.length() > 300 && containsSystemInstructions(prompt)) {
            String coreQuery = findUserQuery(prompt);
            return coreQuery != null ? coreQuery : truncate(prompt, 200);
        }
        
        return truncate(prompt, 300);
    }
    
    /**
     * Extrai resposta limpa removendo metadados técnicos
     */
    private String extractCoreResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "[EMPTY_RESPONSE]";
        }
        
        // Se é chamada de função, simplificar
        if (response.startsWith("FUNCTION_CALL:") || response.trim().startsWith("[{")) {
            return "[FUNCTION_CALL]";
        }
        
        // Remove prefixos técnicos comuns
        String cleaned = response;
        String[] prefixesToRemove = {
            "THOUGHT:", "ACTION:", "OBSERVATION:", "FINAL ANSWER:",
            "Based on the tool execution:", "Tool result:"
        };
        
        for (String prefix : prefixesToRemove) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length()).trim();
                break;
            }
        }
        
        return truncate(cleaned, 500);
    }
    
    /**
     * Verifica se prompt contém instruções de sistema
     */
    private boolean containsSystemInstructions(String prompt) {
        String[] indicators = {
            "You are", "AVAILABLE TOOLS", "INSTRUCTIONS", "Based on your thinking",
            "ORIGINAL QUESTION", "EXECUTION HISTORY", "Now think about"
        };
        
        String upperPrompt = prompt.toUpperCase();
        for (String indicator : indicators) {
            if (upperPrompt.contains(indicator.toUpperCase())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Tenta encontrar a query real do usuário no prompt
     */
    private String findUserQuery(String prompt) {
        String[] lines = prompt.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            // Procurar por linhas que parecem queries do usuário
            if (line.length() > 10 && 
                !line.startsWith("You are") &&
                !line.startsWith("AVAILABLE") &&
                !line.startsWith("INSTRUCTIONS") &&
                !line.startsWith("USER QUERY:") &&
                !line.startsWith("ORIGINAL QUESTION:") &&
                !line.contains("THOUGHT") &&
                !line.contains("ACTION") &&
                !line.contains("tool") &&
                !line.startsWith("-") &&
                !line.startsWith("*")) {
                
                // Se parece com uma query real
                if (line.contains("?") || line.contains("what") || line.contains("how") || 
                    line.contains("quando") || line.contains("como") || line.contains("que")) {
                    return line;
                }
            }
        }
        
        // Fallback: pegar primeira linha substancial
        for (String line : lines) {
            line = line.trim();
            if (line.length() > 20 && !line.contains("You are") && !line.contains("AVAILABLE")) {
                return line;
            }
        }
        
        return null;
    }
    
    /**
     * Trunca texto mantendo legibilidade
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        
        // Tentar quebrar em palavra completa
        int lastSpace = text.lastIndexOf(' ', maxLength - 3);
        if (lastSpace > maxLength / 2) {
            return text.substring(0, lastSpace) + "...";
        }
        
        return text.substring(0, maxLength - 3) + "...";
    }
}

// Classes auxiliares para serialização/desserialização JSON

@JsonInclude(JsonInclude.Include.NON_NULL)
class OpenAiRequestWithTools {
    public String model;
    public Message[] messages;
    public Tool[] tools;
    public String tool_choice = "auto";

    public OpenAiRequestWithTools(String prompt, FunctionDeclaration[] functions, String model) {
        this.model = model;
        this.messages = new Message[]{new Message("user", prompt)};
        this.tools = functions != null && functions.length > 0 ?
                java.util.Arrays.stream(functions).map(Tool::new).toArray(Tool[]::new) : null;
    }

    private static class Message {
        public String role;
        public String content;
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class Tool {
        public String type = "function";
        public Function function;
        public Tool(FunctionDeclaration funcDecl) {
            this.function = new Function(funcDecl);
        }
    }

    private static class Function {
        public String name;
        public String description;
        public Map<String, Object> parameters;

        public Function(FunctionDeclaration funcDecl) {
            this.name = funcDecl.name;
            this.description = funcDecl.description;
            this.parameters = convertToJsonSchema(funcDecl.parameters);
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

            if (params.required != null) {
                if (params.required instanceof List) {
                    schema.put("required", params.required);
                } else if (params.required instanceof String[]) {
                    schema.put("required", List.of((String[]) params.required));
                } else {
                    // Em caso de tipo inesperado, logar um aviso ou lançar uma exceção.
                    // Para robustez, você pode converter para uma lista de strings
                    // ou simplesmente ignorar o campo para evitar a falha.
                    // A solução abaixo simplesmente ignora, o que é mais seguro.
                }
            }

            return schema;
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class OpenAiResponseWithFunctions {
    public Choice[] choices;
    public Error error;

    public FunctionCallResult parseResult() {
        if (error != null) {
            return new FunctionCallResult("OpenAI API error: " + error.message);
        }

        if (this.choices != null && this.choices.length > 0) {
            Choice choice = this.choices[0];
            if (choice.message != null) {
                if (choice.message.tool_calls != null && choice.message.tool_calls.length > 0) {
                    ToolCall toolCall = choice.message.tool_calls[0];
                    Map<String, Object> args = parseArguments(toolCall.function.arguments);
                    return new FunctionCallResult(toolCall.function.name, args);
                } else if (choice.message.content != null) {
                    return new FunctionCallResult(choice.message.content);
                }
            }
        }
        return null;
    }

    private Map<String, Object> parseArguments(String arguments) {
        try {
            return new ObjectMapper().readValue(arguments, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Choice {
        public Message message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Message {
        public String content;
        public String role;
        public ToolCall[] tool_calls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ToolCall {
        public Function function;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Function {
        public String name;
        public String arguments;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Error {
        public String message;
    }
}