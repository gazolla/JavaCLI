package com.gazapps.mcp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Classe responsável por fornecer inteligência dinâmica baseada em metadados MCP,
 * eliminando a necessidade de código hardcoded no pacote de inference.
 * 
 * RESPONSABILIDADES:
 * - Analisar metadados MCP para extrair informações inteligentes
 * - Fornecer configurações dinâmicas baseadas em dados reais dos servidores
 * - Detectar capacidades e características das ferramentas
 * - NÃO gerar prompts (responsabilidade das classes de inference)
 */
public class MCPIntelligence {
    private static final Logger logger = LoggerFactory.getLogger(MCPIntelligence.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final MCPService mcpService;
    private final MCPServers mcpServers;
    
    // Cache simples para otimização
    private final Map<String, JsonNode> schemaCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> entityCache = new ConcurrentHashMap<>();
    
    /**
     * Construtor com dependências MCP.
     */
    public MCPIntelligence(MCPService mcpService, MCPServers mcpServers) {
        this.mcpService = Objects.requireNonNull(mcpService, "MCPService não pode ser nulo");
        this.mcpServers = Objects.requireNonNull(mcpServers, "MCPServers não pode ser nulo");
    }
    
    // ========== CONFIGURAÇÕES DINÂMICAS ==========
    
    /**
     * Determina número ideal de tentativas baseado na prioridade do servidor MCP.
     */
    public int getOptimalRetries(String toolName) {
        try {
            MCPService.ServerConfig serverConfig = getServerConfigForTool(toolName);
            if (serverConfig == null) return 2; // fallback mínimo
            
            return switch (serverConfig.priority) {
                case HIGH -> 5;
                case MEDIUM -> 3;
                case LOW -> 2;
                case UNCLASSIFIED -> 2;
            };
        } catch (Exception e) {
            logger.warn("Erro ao determinar retries para {}: {}", toolName, e.getMessage());
            return 2;
        }
    }
    
    /**
     * Obtém timezone padrão baseado no environment do servidor MCP.
     */
    public String getDefaultTimezone(String toolName) {
        try {
            MCPService.ServerConfig serverConfig = getServerConfigForTool(toolName);
            if (serverConfig == null) return "UTC";
            
            // Verifica environment do servidor
            String timezone = serverConfig.environment.get("TIMEZONE");
            if (timezone != null && !timezone.isEmpty()) {
                return timezone;
            }
            
            // Fallback baseado na descrição do servidor
            String description = serverConfig.description.toLowerCase();
            if (description.contains("brazil") || description.contains("brasil")) {
                return "America/Sao_Paulo";
            }
            if (description.contains("europe")) {
                return "Europe/London";
            }
            if (description.contains("asia")) {
                return "Asia/Tokyo";
            }
            
            return "UTC";
        } catch (Exception e) {
            logger.warn("Erro ao determinar timezone para {}: {}", toolName, e.getMessage());
            return "UTC";
        }
    }
    
    /**
     * Determina comprimento ideal de cadeia baseado na complexidade detectada.
     */
    public int getOptimalChainLength(String query) {
        try {
            int toolCount = countPotentialTools(query);
            boolean hasMultipleServers = requiresMultipleServers(query);
            
            if (hasMultipleServers) return 5;
            if (toolCount > 2) return 4;
            if (toolCount > 1) return 3;
            return 2;
        } catch (Exception e) {
            logger.warn("Erro ao determinar chain length: {}", e.getMessage());
            return 3;
        }
    }
    
    // ========== ANÁLISE DE COMPLEXIDADE ==========
    
    /**
     * Determina se query é complexa baseado em análise das ferramentas MCP disponíveis.
     */
    public boolean isComplexQuery(String query) {
        try {
            return countPotentialTools(query) > 1 || 
                   requiresMultipleServers(query) ||
                   hasComplexEntityRequirements(query);
        } catch (Exception e) {
            logger.warn("Erro ao analisar complexidade: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Conta quantas ferramentas diferentes podem ser necessárias para a query.
     */
    public int countPotentialTools(String query) {
        if (mcpServers.mcpClients.isEmpty()) return 0;
        
        int count = 0;
        String queryLower = query.toLowerCase();
        
        // Analisa ferramentas disponíveis usando dados MCP reais
        for (String serverName : mcpServers.mcpClients.keySet()) {
            try {
                List<Tool> tools = mcpService.discoverServerTools(mcpServers.getClient(serverName));
                for (Tool tool : tools) {
                    if (isToolRelevantForQuery(queryLower, tool, serverName)) {
                        count++;
                    }
                }
            } catch (Exception e) {
                logger.debug("Erro ao analisar ferramentas do servidor {}: {}", serverName, e.getMessage());
            }
        }
        
        return count;
    }
    
    /**
     * Verifica se query requer ferramentas de múltiplos servidores.
     */
    public boolean requiresMultipleServers(String query) {
        Set<String> serversNeeded = new HashSet<>();
        String queryLower = query.toLowerCase();
        
        for (String serverName : mcpServers.mcpClients.keySet()) {
            try {
                List<Tool> tools = mcpService.discoverServerTools(mcpServers.getClient(serverName));
                boolean serverHasRelevantTool = tools.stream()
                    .anyMatch(tool -> isToolRelevantForQuery(queryLower, tool, serverName));
                
                if (serverHasRelevantTool) {
                    serversNeeded.add(serverName);
                }
            } catch (Exception e) {
                logger.debug("Erro ao verificar servidor {}: {}", serverName, e.getMessage());
            }
        }
        
        return serversNeeded.size() > 1;
    }
    
    // ========== ANÁLISE DE ENTIDADES E CAPACIDADES ==========
    
    /**
     * Extrai entidades suportadas por uma ferramenta baseado em seu schema MCP real.
     */
    public Set<String> extractSupportedEntities(String toolName) {
        return entityCache.computeIfAbsent(toolName, name -> {
            Set<String> entities = new HashSet<>();
            
            try {
                JsonNode schema = getToolSchema(name);
                if (schema != null && schema.has("properties")) {
                    JsonNode properties = schema.get("properties");
                    
                    // Analisa parâmetros do schema para detectar tipos de entidade
                    properties.fieldNames().forEachRemaining(paramName -> {
                        String paramNameLower = paramName.toLowerCase();
                        
                        if (paramNameLower.contains("url") || paramNameLower.contains("link")) {
                            entities.add("URL");
                        }
                        if (paramNameLower.contains("file") || paramNameLower.contains("path")) {
                            entities.add("FILE");
                        }
                        if (paramNameLower.contains("location") || paramNameLower.contains("latitude") || 
                            paramNameLower.contains("longitude") || paramNameLower.contains("city")) {
                            entities.add("LOCATION");
                        }
                        if (paramNameLower.contains("time") || paramNameLower.contains("date") || 
                            paramNameLower.contains("timezone")) {
                            entities.add("TIME");
                        }
                        if (paramNameLower.contains("email") || paramNameLower.contains("mail")) {
                            entities.add("EMAIL");
                        }
                        if (paramNameLower.contains("number") || paramNameLower.contains("amount") || 
                            paramNameLower.contains("count")) {
                            entities.add("NUMBER");
                        }
                    });
                }
            } catch (Exception e) {
                logger.debug("Erro ao extrair entidades para {}: {}", name, e.getMessage());
            }
            
            return entities;
        });
    }
    
    /**
     * Calcula relevância de uma ferramenta para uma query baseado em dados MCP.
     */
    public double calculateToolRelevance(String query, String toolName) {
        try {
            String serverName = mcpServers.getServerForTool(toolName);
            if (serverName == null) return 0.0;
            
            // Usa dados reais do servidor MCP
            MCPService.ServerConfig serverConfig = getServerConfigForTool(toolName);
            Tool tool = getToolFromServer(toolName, serverName);
            
            if (tool == null || serverConfig == null) return 0.0;
            
            double relevance = 0.0;
            String queryLower = query.toLowerCase();
            
            // Análise baseada na descrição da ferramenta
            if (tool.description() != null) {
                String description = tool.description().toLowerCase();
                String[] queryWords = queryLower.split("\\s+");
                String[] descWords = description.split("\\s+");
                
                for (String queryWord : queryWords) {
                    if (queryWord.length() > 3) {
                        for (String descWord : descWords) {
                            if (queryWord.equals(descWord)) {
                                relevance += 0.3;
                            } else if (queryWord.contains(descWord) || descWord.contains(queryWord)) {
                                relevance += 0.1;
                            }
                        }
                    }
                }
            }
            
            // Análise baseada na descrição do servidor
            if (serverConfig.description != null) {
                String serverDesc = serverConfig.description.toLowerCase();
                if (queryLower.contains(serverDesc) || serverDesc.contains(queryLower)) {
                    relevance += 0.4;
                }
            }
            
            // Análise baseada nas entidades suportadas
            Set<String> supportedEntities = extractSupportedEntities(toolName);
            Set<String> queryEntities = detectEntitiesInQuery(query);
            
            for (String entity : queryEntities) {
                if (supportedEntities.contains(entity)) {
                    relevance += 0.2;
                }
            }
            
            return Math.min(relevance, 1.0);
        } catch (Exception e) {
            logger.debug("Erro ao calcular relevância para {}: {}", toolName, e.getMessage());
            return 0.0;
        }
    }
    
    // ========== DETECÇÃO DE ERROS BASEADA EM SCHEMA ==========
    
    /**
     * Verifica se erro é de validação baseado no schema real da ferramenta.
     */
    public boolean isValidationError(String errorMessage, String toolName) {
        if (errorMessage == null) return false;
        
        try {
            JsonNode schema = getToolSchema(toolName);
            if (schema == null) {
                // Fallback para detecção básica
                return errorMessage.toLowerCase().contains("validation") ||
                       errorMessage.toLowerCase().contains("required") ||
                       errorMessage.toLowerCase().contains("invalid");
            }
            
            // Verifica se erro menciona campos do schema
            if (schema.has("properties")) {
                JsonNode properties = schema.get("properties");
                for (Iterator<String> fieldNames = properties.fieldNames(); fieldNames.hasNext();) {
                    String fieldName = fieldNames.next();
                    if (errorMessage.contains(fieldName)) {
                        return true;
                    }
                }
            }
            
            // Verifica se erro menciona campos obrigatórios
            if (schema.has("required")) {
                JsonNode required = schema.get("required");
                for (JsonNode reqField : required) {
                    if (errorMessage.contains(reqField.asText())) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            logger.debug("Erro ao verificar validação para {}: {}", toolName, e.getMessage());
            return errorMessage.toLowerCase().contains("validation");
        }
    }
    
    // ========== MÉTODOS AUXILIARES ==========
    
    /**
     * Obtém configuração do servidor para uma ferramenta específica.
     */
    private MCPService.ServerConfig getServerConfigForTool(String toolName) {
        String serverName = mcpServers.getServerForTool(toolName);
        if (serverName == null) return null;
        
        return mcpServers.mcpServers.stream()
            .filter(config -> config.name.equals(serverName))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Obtém schema real da ferramenta do servidor MCP.
     */
    private JsonNode getToolSchema(String toolName) {
        return schemaCache.computeIfAbsent(toolName, name -> {
            try {
                String serverName = mcpServers.getServerForTool(name);
                if (serverName == null) return null;
                
                String originalToolName = name.substring(serverName.length() + 1);
                List<Tool> tools = mcpService.discoverServerTools(mcpServers.getClient(serverName));
                
                for (Tool tool : tools) {
                    if (tool.name().equals(originalToolName)) {
                    	Object inputSchema = tool.inputSchema();
                    	if (inputSchema != null) {
                    	    return objectMapper.valueToTree(inputSchema);
                    	}
                    }
                }
                
                return null;
            } catch (Exception e) {
                logger.debug("Erro ao obter schema para {}: {}", name, e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Obtém ferramenta específica de um servidor.
     */
    private Tool getToolFromServer(String toolName, String serverName) {
        try {
            String originalToolName = toolName.substring(serverName.length() + 1);
            List<Tool> tools = mcpService.discoverServerTools(mcpServers.getClient(serverName));
            
            return tools.stream()
                .filter(tool -> tool.name().equals(originalToolName))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            logger.debug("Erro ao obter tool {} do servidor {}: {}", toolName, serverName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Verifica se ferramenta é relevante para query baseado em dados MCP reais.
     */
    private boolean isToolRelevantForQuery(String queryLower, Tool tool, String serverName) {
        // Verifica descrição da ferramenta
        if (tool.description() != null) {
            String description = tool.description().toLowerCase();
            String[] queryWords = queryLower.split("\\s+");
            for (String word : queryWords) {
                if (word.length() > 3 && description.contains(word)) {
                    return true;
                }
            }
        }
        
        // Verifica nome da ferramenta
        if (tool.name() != null) {
            String toolNameLower = tool.name().toLowerCase();
            if (queryLower.contains(toolNameLower) || toolNameLower.contains(queryLower)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detecta entidades presentes na query.
     */
    private Set<String> detectEntitiesInQuery(String query) {
        Set<String> entities = new HashSet<>();
        String queryLower = query.toLowerCase();
        
        if (queryLower.matches(".*https?://.*")) entities.add("URL");
        if (queryLower.matches(".*\\w+\\.(txt|json|xml|csv|log).*")) entities.add("FILE");
        if (queryLower.matches(".*\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b.*")) entities.add("LOCATION");
        if (queryLower.matches(".*\\d+.*")) entities.add("NUMBER");
        if (queryLower.matches(".*(?:hoje|agora|now|time|hora|when|date).*")) entities.add("TIME");
        if (queryLower.matches(".*\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b.*")) entities.add("EMAIL");
        
        return entities;
    }
    
    /**
     * Verifica se query tem requisitos complexos de entidade.
     */
    private boolean hasComplexEntityRequirements(String query) {
        Set<String> entities = detectEntitiesInQuery(query);
        return entities.size() > 1;
    }
    
    /**
     * Limpa cache para refresh de dados.
     */
    public void clearCache() {
        schemaCache.clear();
        entityCache.clear();
        logger.info("Cache do MCPIntelligence limpo");
    }
    
    /**
     * Retorna estatísticas de uso para debugging.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedSchemas", schemaCache.size());
        stats.put("cachedEntities", entityCache.size());
        stats.put("connectedServers", mcpServers.mcpClients.size());
        return stats;
    }
}