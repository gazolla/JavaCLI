package com.gazapps.inference.bigprompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gazapps.config.Config;
import com.gazapps.inference.Inference;
import com.gazapps.llm.Llm;
import com.gazapps.llm.function.FunctionDeclaration;
import com.gazapps.mcp.MCPService;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

public class SimpleSequential implements Inference {

    private static final Logger logger = LoggerFactory.getLogger(SimpleSequential.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MCPService mcpService;
    private MCPServers mcpServers;
    private Llm llmService;
    private List<FunctionDeclaration> availableMcpTools;
    private Object conversationMemory; // Referência para memória de conversação

    public SimpleSequential(Llm llmService, MCPService mcpService, MCPServers mcpServers) {
        this.llmService = llmService;
        this.mcpService = mcpService;
        this.mcpServers = mcpServers;
        initializeAvailableMcpTools();
    }

    @Override
    public String processQuery(String query) {
        try {
            FunctionDeclaration[] tools = availableMcpTools != null ? 
                availableMcpTools.toArray(new FunctionDeclaration[0]) : new FunctionDeclaration[0];

            String systemContext = buildSystemPrompt();
            String fullPrompt = buildPromptWithHistory(systemContext, query);

            String llmResponse = llmService.generateResponse(fullPrompt, List.of(tools));
            // Check for function calls in different formats
            if (llmResponse.startsWith("FUNCTION_CALL:")) {
                String[] parts = llmResponse.split(":", 3);
                if (parts.length >= 3) {
                    String functionName = parts[1];
                    String argsJson = parts[2];
                    Map<String, Object> arguments = objectMapper.readValue(argsJson, Map.class);
                    
                    String toolResult = executeFunction(functionName, arguments);
                    
                    if (toolResult != null) {
                        return processToolResult(query, functionName, toolResult);
                    } else {
                        return "Ferramenta executada sem retorno.";
                    }
                }
            }
            
            // Check for JSON array function calls (Groq format)
            if (llmResponse.trim().startsWith("[") && llmResponse.contains("name") && llmResponse.contains("parameters")) {
                // Parse and execute the function call
                try {
                    String cleanedResponse = llmResponse.trim();
                    com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cleanedResponse);
                    if (jsonArray.isArray() && jsonArray.size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode firstCall = jsonArray.get(0);
                        if (firstCall.has("name") && firstCall.has("parameters")) {
                            String functionName = firstCall.get("name").asText();
                            com.fasterxml.jackson.databind.JsonNode paramsNode = firstCall.get("parameters");
                            Map<String, Object> arguments = objectMapper.convertValue(paramsNode, Map.class);
                            
                            String toolResult = executeFunction(functionName, arguments);
                            
                            if (toolResult != null) {
                                return processToolResult(query, functionName, toolResult);
                            } else {
                                return "Ferramenta executada sem retorno.";
                            }
                        }
                    }
                } catch (Exception jsonEx) {
                    logger.warn("Erro ao processar JSON function call: " + jsonEx.getMessage());
                }
            }
            
            return llmResponse;
        } catch (Exception e) {
            return "Erro no processamento: " + e.getMessage();
        }
    }

    @Override
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        Config config = new Config();

        prompt.append(
                """
                You are an AI assistant designed to use MCP (Model Context Protocol) tools.
                Your primary function is to analyze user queries and invoke available tools to build complete and useful responses.
                
                KNOWLEDGE USAGE:
                - Use your built-in knowledge to fill in missing parameters when possible
                - For common locations, use these coordinates:
                  * NYC/New York: latitude=40.7128, longitude=-74.0060
                  * London: latitude=51.5074, longitude=-0.1278
                  * Paris: latitude=48.8566, longitude=2.3522
                - Only ask for clarification when you genuinely don't know the required information
                - Combine your knowledge with tool capabilities for complete responses
                
                WHEN TO USE TOOLS:
                - Use memory tools ONLY when user explicitly asks to save, store, remember, or retrieve specific information
                - Use weather tools when user asks for current weather, forecasts, or weather conditions
                - Use filesystem tools when user asks to read, write, create, or manage files
                - For general knowledge questions (planets, capitals, definitions), answer directly without tools
                - If unsure whether to use tools, answer directly first
                
                IMPORTANT: Do not use tools for basic factual questions that you can answer with your knowledge.

                        """);

        if (mcpServers != null) {
            try {
                List<Object> connectedServers = new ArrayList<>();
                
                connectedServers = mcpServers.mcpServers.stream()
                    .filter(server -> server.enabled && mcpServers.mcpClients.containsKey(server.name))
                    .collect(Collectors.toList());

                if (connectedServers.size() > 1) {
                    prompt.append("""
                                    TOOL EXECUTION METHODOLOGY:
                        1. ANALYZE the user query to identify required operations
                        2. PLAN the sequence of tools needed
                        3. EXECUTE tools in order, using real results as inputs for subsequent tools
                        4. BUILD final response using actual data from tool executions

                        TOOL CHAINING RULES:
                        - Execute ONE tool at a time
                        - Wait for each tool to complete and return actual results
                        - Use the REAL OUTPUT from Tool N as input parameter for Tool N+1
                        - Continue chaining until the complete workflow is finished
                        - Your final response must incorporate ACTUAL DATA from tool executions

                        CRITICAL: NEVER use placeholder text like ${tool_name} in your responses.
                        Always use the real data returned by tools.

                        WORKFLOW EXAMPLE:
                        User: "Get weather data and save to file"
                        Step 1: Execute weather_get_forecast → receives actual JSON weather data
                        Step 2: Execute filesystem_write_file with the REAL weather JSON as content
                        Step 3: Respond with summary of what was actually saved

                        CHAINING EXAMPLE:
                        User: "Read memory, process the data, and save to database"
                        Step 1: Execute memory_read_graph → get actual JSON: {"entities": [...]}
                        Step 2: Execute data_process with the real JSON from step 1
                        Step 3: Execute database_save with processed results from step 2
                        Step 4: Respond with what was actually accomplished

                        MULTI-MCP COORDINATION:
                        - Plan complete workflows before execution
                        - Chain tools logically: READ → PROCESS → TRANSFORM → STORE
                        - Each tool receives real output from previous tool as input
                        - Never save query parameters - save actual results
                        - If a tool fails, inform user and ask if they want to continue

                        DATA FLOW PRINCIPLE:
                        Real Data → Tool A → Real Result A → Tool B → Real Result B → Tool C → Final Output

                                    """);
                }

                if (!connectedServers.isEmpty()) {
                    prompt.append("AVAILABLE MCP SERVERS:\n");

                    for (Object server : connectedServers) {
                    com.gazapps.mcp.MCPService.ServerConfig serverConfig = (com.gazapps.mcp.MCPService.ServerConfig) server;
                    prompt.append("- ").append(serverConfig.name.toUpperCase()).append(": ").append(serverConfig.description);

                        if (serverConfig.name.equals("filesystem") && serverConfig.type.equals("stdio")) {
                    String resolvedPath = config.resolveFilesystemPath();
                    prompt.append("\n  Base path: ").append(resolvedPath);
                    prompt.append("\n  IMPORTANT: Always use complete absolute paths starting with: ")
                        .append(resolvedPath);
                    prompt.append("\n  Correct example: '").append(resolvedPath);
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    prompt.append("\\filename.ext'");
                    } else {
                    prompt.append("/filename.ext'");
                    }
                    prompt.append("\n  NEVER use relative paths");
                    }

                    if (serverConfig.type.equals("http")) {
                    prompt.append("\n  Transport: HTTP - Online service for external data and operations");
                    } else if (serverConfig.type.equals("stdio")) {
                    prompt.append("\n  Transport: STDIO - Local process for system operations");
                    }

                    if (!serverConfig.environment.isEmpty()) {
                    long configCount = serverConfig.environment.entrySet().stream()
                        .filter(entry -> !entry.getKey().startsWith("REQUIRES_")).count();
                    if (configCount > 0) {
                    prompt.append("\n  Configured with environment variables for enhanced functionality");
                    }
                    }

                        prompt.append("\n\n");
                    }

                    prompt.append("""
                            TOOL NAMING CONVENTION:
                            Tools follow the pattern: {server}_{original_function}
                            Examples: 'filesystem_read_file', 'weather-nws_get-forecast', 'memory_create_entities'

                            AVAILABLE TOOLS:
                            """);
                    
                    if (availableMcpTools != null) {
                        for (FunctionDeclaration tool : availableMcpTools) {
                            prompt.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
                        }
                    }
                    
                    prompt.append("""

                            BEST PRACTICES:
                            - Always use complete absolute paths for file operations
                            - Verify paths are within the allowed server scope
                            - For multiple operations, combine tools intelligently
                            - Always provide clear feedback about executed operations
                            - If an operation fails, explain why and suggest alternatives

                            """);
                }
            } catch (Exception e) {
                logger.error("Erro ao construir prompt do sistema", e);
            }
        }

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            prompt.append("""
                    SYSTEM: Windows
                    - Use backslashes (\\) in paths
                    - Absolute paths start with drive letter (e.g., C:\\)

                    """);
        } else {
            prompt.append("""
                    SYSTEM: Unix/Linux
                    - Use forward slashes (/) in paths
                    - Absolute paths start with forward slash (/)

                    """);
        }

        logger.debug("{}", prompt.toString());

        return prompt.toString();
    }


    
    /**
     * Define a referência para a memória de conversação.
     * Usado para construir prompts com histórico.
     */
    public void setConversationMemory(Object conversationMemory) {
        this.conversationMemory = conversationMemory;
    }
    
    /**
     * Constrói o prompt incluindo o histórico de conversação.
     */
    private String buildPromptWithHistory(String systemContext, String currentQuery) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemContext);
        
        // Adicionar histórico de conversação se disponível
        if (conversationMemory != null) {
            try {
                // Usar reflection para acessar getRecentMessages()
                Object recentMessages = conversationMemory.getClass()
                    .getMethod("getRecentMessages")
                    .invoke(conversationMemory);
                
                if (recentMessages instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> messages = (List<Object>) recentMessages;
                    
                    if (!messages.isEmpty()) {
                        promptBuilder.append("\n\nHISTÓRICO DA CONVERSAÇÃO:\n");
                        
                        // Limitar a últimas 10 mensagens para não exceder limite de tokens
                        int startIndex = Math.max(0, messages.size() - 10);
                        
                        for (int i = startIndex; i < messages.size(); i++) {
                            Object message = messages.get(i);
                            String role = (String) message.getClass().getMethod("getRole").invoke(message);
                            String content = (String) message.getClass().getMethod("getContent").invoke(message);
                            
                            if ("user".equals(role)) {
                                promptBuilder.append("Usuário: ").append(content).append("\n");
                            } else if ("assistant".equals(role)) {
                                promptBuilder.append("Assistente: ").append(content).append("\n");
                            }
                        }
                        
                        promptBuilder.append("\nPERGUNTA ATUAL:\n");
                    }
                }
                
            } catch (Exception e) {
                logger.warn("⚠️ Erro ao acessar histórico de conversação: {}", e.getMessage());
                // Se houver erro, continua sem histórico
            }
        }
        
        promptBuilder.append(currentQuery);
        return promptBuilder.toString();
    }

    private void initializeAvailableMcpTools() {
        if (mcpServers == null) return;
        
        try {
            availableMcpTools = new ArrayList<>();
            
            for (Map.Entry<String, McpSyncClient> entry : mcpServers.mcpClients.entrySet()) {
                String serverName = entry.getKey();
                McpSyncClient client = entry.getValue();
                ListToolsResult toolsResult = client.listTools();
                List<io.modelcontextprotocol.spec.McpSchema.Tool> serverTools = toolsResult.tools();
                for (io.modelcontextprotocol.spec.McpSchema.Tool mcpTool : serverTools) {
                    String toolName = mcpTool.name();
                    String namespacedToolName = serverName + "_" + toolName;
                    FunctionDeclaration geminiFunction = llmService.convertMcpToolToFunction(mcpTool, namespacedToolName);
                    availableMcpTools.add(geminiFunction);
                }
            }
        } catch (Exception e) {
            logger.error("Erro ao inicializar ferramentas MCP", e);
            availableMcpTools = new ArrayList<>();
        }
    }



    private String executeFunction(String functionName, Map<String, Object> args) {
        try {
                String serverName = mcpServers.getServerForTool(functionName);
                McpSyncClient client = mcpServers.getClient(serverName);
                String originalToolName = functionName.substring(serverName.length() + 1);
                String result = mcpService.executeToolByName(client, originalToolName, args);
                return result;

        } catch (Exception e) {
            String errorMsg = "Erro ao executar a ferramenta: " + e.getMessage();
            System.out.println(errorMsg);
            e.printStackTrace();
            return errorMsg;
        }
    }
    
    private String processToolResult(String originalQuery, String toolName, String toolResult) {
        try {
            String processingPrompt = String.format("""
                You are a helpful assistant that processes raw data from tools and creates friendly, natural responses.
                
                ORIGINAL USER QUESTION: "%s"
                TOOL USED: %s
                RAW TOOL RESULT:
                %s
                
                INSTRUCTIONS:
                - Create a natural, conversational response in Portuguese
                - Extract the most relevant information for the user's question
                - Make it easy to understand and well-formatted
                - For weather data: highlight current conditions and near-term forecast
                - For file operations: confirm what was done
                - For memory operations: summarize what was stored/retrieved
                - Keep it concise but informative
                
                RESPONSE:
                """, originalQuery, toolName, toolResult);
            
            String response = llmService.generateResponse(processingPrompt, null);
            
            return response != null && !response.isEmpty() ? 
                   response : 
                   "Não foi possível processar o resultado.";
        } catch (Exception e) {
            return "Erro ao processar resultado da ferramenta: " + e.getMessage();
        }
    }
    
    @Override
    public void close() {
        if (mcpServers instanceof AutoCloseable) {
            try {
                ((AutoCloseable) mcpServers).close();
            } catch (Exception e) {
                logger.warn("Erro ao fechar MCPServers: {}", e.getMessage());
            }
        }
    }


}
