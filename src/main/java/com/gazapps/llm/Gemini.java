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

public class Gemini implements Llm {

    private static final Logger logger = LoggerFactory.getLogger(Gemini.class);
    private static final Logger conversationLogger = Config.getLlmConversationLogger("gemini");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configurações (agora carregadas da Config em vez de hardcoded)
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeout;
    private boolean debug;

    public Gemini() {
        // Carregar configurações da Config
        Config config = new Config();
        
        // Auto-criar arquivo se necessário
        config.createConfigFileIfNeeded();
        
        Map<String, String> geminiConfig = config.getGeminiConfig();
        
        this.baseUrl = geminiConfig.get("baseUrl");
        this.apiKey = geminiConfig.get("apiKey");
        this.model = geminiConfig.get("model");
        this.timeout = Integer.parseInt(geminiConfig.get("timeout"));
        this.debug = Boolean.parseBoolean(geminiConfig.get("debug"));
        
        // Validação automática
        config.isLlmConfigValid("gemini");
        
        // Debug se habilitado
        if (debug) {
            logger.debug("🔧 Gemini Debug:");
            logger.debug("   URL: {}", baseUrl);
            logger.debug("   Modelo: {}", model);
            logger.debug("   Timeout: {}s", timeout);
            logger.debug("   API Key: {}", (apiKey.isEmpty() ? "❌ Não configurada" : "✅ Configurada"));
        }
    }

    @Override
    public String generateResponse(String prompt, List<FunctionDeclaration> functions) {
        try {
            // Basic input validation
            if (prompt == null || prompt.trim().isEmpty()) {
                throw new InputException("Prompt cannot be empty");
            }
            
            if (prompt.length() > 32000) {
                throw new InputException("Prompt too long for Gemini (maximum 32,000 characters)");
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
            conversationLogger.info("=== GEMINI INTERACTION ===");
            conversationLogger.info("INPUT: {}", cleanInput);
            conversationLogger.info("OUTPUT: {}", cleanOutput);
            conversationLogger.info(""); // linha em branco
            
            return finalResult;
            
        } catch (InputException e) {
            throw new RuntimeException(e); // Preserve input validation exception
            
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(new LLMException("Connection timeout", "Gemini", e));
            
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(new LLMException("Could not connect to service", "Gemini", e));
            
        } catch (java.io.IOException e) {
            throw new RuntimeException(new LLMException("Communication error", "Gemini", e));
            
        } catch (Exception e) {
            throw new RuntimeException(new LLMException("Unexpected error: " + e.getMessage(), "Gemini", e));
        }
    }

    @Override
    public String getProviderName() {
        return "Gemini";
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

    public HttpRequest buildRequest(String prompt, FunctionDeclaration[] tools) throws Exception {
        GeminiRequestWithTools requestObject = new GeminiRequestWithTools(prompt, tools);
        String requestBody = objectMapper.writeValueAsString(requestObject);

        return HttpRequest.newBuilder()
                .uri(new URI(baseUrl + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    public HttpResponse<String> sendMessage(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (debug) {
            logger.debug("Status Code: {}", response.statusCode());
            logger.debug("JSON de Resposta: {}", response.body());
        }
        return response;
    }

    public FunctionCallResult extractAnswer(HttpResponse<String> response) throws Exception {
    	 if (response.statusCode() >= 400) {
 	        String errorBody = response.body();
  	        throw new IOException("Erro da API Gemini: " + errorBody);
 	    }
 	    
 	    try {
 	    	GeminiResponseWithFunctions responseObject = objectMapper.readValue(response.body(), GeminiResponseWithFunctions.class);
 	    	return responseObject.parseResult();
 	    } catch (IOException e) {
 	    	throw new IOException("Falha ao processar a resposta da API Gemini: " + e.getMessage(), e);
 	    }  
    }
    
    public FunctionDeclaration convertMcpToolToFunction(io.modelcontextprotocol.spec.McpSchema.Tool mcpTool, String namespacedName) {
        String description = mcpTool.description() != null ? mcpTool.description() : "Sem descrição disponível";
        JsonNode inputSchemaNode = mcpTool.inputSchema() != null ? objectMapper.valueToTree(mcpTool.inputSchema()) : objectMapper.createObjectNode();

        Map<String, ParameterDetails> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        if (inputSchemaNode.has("properties")) {
            JsonNode propertiesNode = inputSchemaNode.get("properties");
            propertiesNode.fields().forEachRemaining(entry -> {
                String paramName = entry.getKey();
                JsonNode paramSchema = entry.getValue();
                String paramType = paramSchema.has("type") ? paramSchema.get("type").asText() : "string";
                String paramDescription = paramSchema.has("description") ? paramSchema.get("description").asText() : "Sem descrição";
                properties.put(paramName, new ParameterDetails(paramType, paramDescription));
            });
        }

        if (inputSchemaNode.has("required")) {
            JsonNode requiredNode = inputSchemaNode.get("required");
            requiredNode.forEach(node -> required.add(node.asText()));
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

@JsonInclude(JsonInclude.Include.NON_NULL)
class GeminiRequestWithTools {
    public Content[] contents;
    public Tools tools;

    public GeminiRequestWithTools(String prompt, FunctionDeclaration[] tools) {
        this.contents = new Content[]{new Content(prompt)};
        this.tools = (tools != null && tools.length > 0) ? new Tools(tools) : null;
    }

    private static class Content {
        public String role;
        public Part[] parts;
        public Content(String prompt) {
            this.role = "user";
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
        public Tools(FunctionDeclaration[] functionDeclarations) {
            this.functionDeclarations = functionDeclarations;
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class GeminiResponseWithFunctions {
    public Candidate[] candidates;
    
    public FunctionCallResult parseResult() {
        if (this.candidates != null && this.candidates.length > 0) {
            Candidate candidate = this.candidates[0];
            if (candidate.content != null && candidate.content.parts != null && candidate.content.parts.length > 0) {
                PartResponse part = candidate.content.parts[0];
                if (part.functionCall != null) {
                    return new FunctionCallResult(part.functionCall.name, part.functionCall.args);
                } else if (part.text != null) {
                    return new FunctionCallResult(part.text);
                }
            }
        }
        return null;
    }

    private static class Candidate {
        public ContentWithFunctions content;
        public String finishReason;   
        public Double avgLogprobs;
        public Object citationMetadata;  // Campo adicionado para compatibilidade
    }

    private static class ContentWithFunctions {
        public PartResponse[] parts;
        public String role;  
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class PartResponse {
        public String text;
        public FunctionCall functionCall;
    }

    private static class FunctionCall {
        public String name;
        public Map<String, Object> args;
    }
    
    public static class ErrorResponse {
        public int code;
        public String message;
        public String status;
    }
}
