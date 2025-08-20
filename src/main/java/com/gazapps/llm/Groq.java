package com.gazapps.llm;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.llm.function.FunctionCallResult;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.llm.function.FunctionParameters;
import com.gazapps.llm.function.ParameterDetails;
import com.gazapps.exceptions.LLMException;
import com.gazapps.exceptions.InputException;

public class Groq implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Groq.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("groq");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configurações (agora carregadas da Config em vez de hardcoded)
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;

    public Groq() {
        // Carregar configurações da Config
        Config config = new Config();
        
        // Auto-criar arquivo se necessário
        config.createConfigFileIfNeeded();
        
        Map<String, String> groqConfig = config.getGroqConfig();
        
        this.baseUrl = groqConfig.get("baseUrl");
        this.apiKey = groqConfig.get("apiKey");
        this.model = groqConfig.get("model");
        this.timeout = Integer.parseInt(groqConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(groqConfig.get("debug"));
        
        // Validação automática
        config.isLlmConfigValid("groq");
        
        // Debug se habilitado
        if (debug) {
            logger.debug("🔧 Groq Debug:");
            logger.debug("   URL: {}", baseUrl);
            logger.debug("   Model: {}", model);
            logger.debug("   Timeout: {}s", timeout);
            logger.debug("   API Key: {}", (apiKey.isEmpty() ? "❌ Not configured" : "✅ Configured"));
        }
    }

    @Override
    public String generateResponse(String prompt, List<FunctionDeclaration> functions) {
        try {
            // Basic input validation
            if (prompt == null || prompt.trim().isEmpty()) {
                throw new InputException("Prompt cannot be empty");
            }
            
            if (prompt.length() > 50000) {
                throw new InputException("Prompt too long for Groq (maximum 50,000 characters)");
            }
            
            // Log da interação limpa
            String cleanInput = extractCorePrompt(prompt);
            
            if (debug) {
                logger.debug("prompt: {}", prompt);
            }
            
            FunctionDeclaration[] tools = functions != null ? functions.toArray(new FunctionDeclaration[0]) : null;
            HttpRequest request = buildRequest(prompt, tools);
            HttpResponse<String> response = sendMessage(request);
            FunctionCallResult result = extractAnswer(response);
            
            String cleanOutput;
            String finalResult;
            
            if (debug) {
                logger.debug("result: {}", result);
            }
            
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
                finalResult = null;
            }
            
            // Log da conversação limpa
            conversationLogger.info("=== GROQ INTERACTION ===");
            conversationLogger.info("INPUT: {}", cleanInput);
            conversationLogger.info("OUTPUT: {}", cleanOutput);
            conversationLogger.info(""); // linha em branco
            
            return finalResult;
            
        } catch (InputException e) {
            throw new RuntimeException(e); // Preserve input validation exception
            
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(new LLMException("Connection timeout", "Groq", e));
            
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(new LLMException("Could not connect to service", "Groq", e));
            
        } catch (java.io.IOException e) {
            throw new RuntimeException(new LLMException("Communication error", "Groq", e));
            
        } catch (Exception e) {
            throw new RuntimeException(new LLMException("Unexpected error: " + e.getMessage(), "Groq", e));
        }
    }

    @Override
    public String getProviderName() {
        return "Groq";
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
                
                // Para arrays, sempre definir items (nunca deixar null)
                Map<String, Object> items = null;
                
                if ("array".equals(paramType)) {
                    Map<String, Object> itemsMap = new HashMap<>();
                    
                    if (paramSchema.has("items")) {
                        JsonNode itemsNode = paramSchema.get("items");
                        
                        if (itemsNode.has("type")) {
                            itemsMap.put("type", itemsNode.get("type").asText());
                        } else {
                            itemsMap.put("type", "string"); // default
                        }
                        
                        if (itemsNode.has("description")) {
                            itemsMap.put("description", itemsNode.get("description").asText());
                        }
                        
                        // Adicionar outras propriedades do items se existirem
                        if (itemsNode.has("properties")) {
                            Map<String, Object> itemProperties = new HashMap<>();
                            JsonNode itemPropsNode = itemsNode.get("properties");
                            itemPropsNode.fields().forEachRemaining(itemEntry -> {
                                JsonNode itemProp = itemEntry.getValue();
                                Map<String, Object> propMap = new HashMap<>();
                                if (itemProp.has("type")) {
                                    propMap.put("type", itemProp.get("type").asText());
                                }
                                if (itemProp.has("description")) {
                                    propMap.put("description", itemProp.get("description").asText());
                                }
                                itemProperties.put(itemEntry.getKey(), propMap);
                            });
                            itemsMap.put("properties", itemProperties);
                        }
                    } else {
                        // Array sem definição de items - usar default mais genérico
                        itemsMap.put("type", "string");
                    }
                    
                    items = itemsMap;
                }
                
                // Usar o construtor direto com items (que pode ser null)
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
    
    
    public HttpRequest buildRequest(String prompt, FunctionDeclaration[] tools) throws Exception {
        GroqRequestWithTools requestObject = new GroqRequestWithTools(prompt, tools, model);
        String requestBody = objectMapper.writeValueAsString(requestObject);
        
        if (debug) {
            logger.debug("🔍 REQUEST BODY (Groq):");
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
            throw new IOException("Groq API error: " + errorBody);
        }
        
        try {
            GroqResponseWithFunctions responseObject = objectMapper.readValue(response.body(), GroqResponseWithFunctions.class);
            return responseObject.parseResult();
        } catch (Exception e) {
            throw new IOException("Failed to process Groq API response: " + e.getMessage(), e);
        }
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

@JsonInclude(JsonInclude.Include.NON_NULL)
class GroqRequestWithTools {
    public String model;
    public Message[] messages;
    public Tool[] tools;

    public GroqRequestWithTools(String prompt, FunctionDeclaration[] functions, String model) {
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
                    
                    // Adicionar items para arrays APENAS se existir e não for null
                    if ("array".equals(details.type) && details.items != null) {
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
                }
            }
            
            return schema;
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class GroqResponseWithFunctions {
    public Choice[] choices;
    public String id;
    public String object;
    public long created;
    public String model;
    public Usage usage;
    public Error error;
    
    public FunctionCallResult parseResult() {
        // If there's an error in the response, return error
        if (error != null) {
            return new FunctionCallResult("Groq API error: " + error.message);
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
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(arguments, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Choice {
        public int index;
        public String finish_reason;
        public Message message;
        public Object logprobs;  // Pode ser null ou objeto complexo
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Message {
        public String content;
        public String role;
        public ToolCall[] tool_calls;
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
        public String arguments;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Error {
        public String message;
        public String type;
        public String code;
    }
}
