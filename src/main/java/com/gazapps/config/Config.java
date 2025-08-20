package com.gazapps.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gazapps.mcp.MCPService;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import ch.qos.logback.classic.filter.ThresholdFilter;

public class Config {
    
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final String CONFIG_FILE = "config/application.properties";
    private static final String MCP_CONFIG_FILE = "config/mcp/.mcp.json";
    
    private Properties properties;
    private ObjectMapper objectMapper = new ObjectMapper();
    private static boolean loggingConfigured = false; // Static para garantir uma única configuração
    
    public Config() {
        // PRIMEIRA COISA: Carregar properties
        loadProperties();
        
        // SEGUNDA COISA: Configurar logging usando as properties
        setupProgrammaticLogging();
    }
    
    private void loadProperties() {
        properties = new Properties();
        try {
            // Try to load from file
            if (Files.exists(Paths.get(CONFIG_FILE))) {
                try (InputStream input = new FileInputStream(CONFIG_FILE)) {
                    properties.load(input);
                    logger.info("📋 Application properties loaded: {}", new File(CONFIG_FILE).getAbsolutePath());
                }
            } else {
                logger.warn("Configuration file not found: {}", CONFIG_FILE);
                createConfigFileIfNeeded();
            }
        } catch (IOException e) {
            logger.error("Error loading configuration: {}", e.getMessage());
        }
    }
    
    public void createConfigFileIfNeeded() {
        try {
            Path configPath = Paths.get(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                createDefaultApplicationProperties();
                logger.info("✅ Default application.properties created");
            }
        } catch (IOException e) {
            logger.error("Error creating config file: {}", e.getMessage());
        }
    }
    
    public void createConfigStructure() {
        try {
            // Create directories if they don't exist
            Files.createDirectories(Paths.get("config/mcp"));
            Files.createDirectories(Paths.get("documents"));
            Files.createDirectories(Paths.get("log"));
            
            // Create MCP configuration if it doesn't exist
            if (!Files.exists(Paths.get(MCP_CONFIG_FILE))) {
                createDefaultMcpConfig();
                logger.info("✅ Default .mcp.json created");
            }
            
            logger.info("📁 Configuration structure verified");
        } catch (IOException e) {
            logger.error("Error creating config structure: {}", e.getMessage());
        }
    }
    
    public Map<String, String> getGroqConfig() {
        Map<String, String> groqConfig = new HashMap<>();
        groqConfig.put("baseUrl", getProperty("groq.base.url", "https://api.groq.com/openai/v1/chat/completions"));
        groqConfig.put("model", getProperty("groq.model", "llama-3.3-70b-versatile"));
        groqConfig.put("timeout", getProperty("groq.timeout", "30"));
        groqConfig.put("debug", getProperty("groq.debug", "true"));
        groqConfig.put("apiKey", getEnvironmentOrProperty("GROQ_API_KEY", "groq.api.key"));
        return groqConfig;
    }
    
    public Map<String, String> getGeminiConfig() {
        Map<String, String> geminiConfig = new HashMap<>();
        geminiConfig.put("baseUrl", getProperty("gemini.base.url", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"));
        geminiConfig.put("model", getProperty("gemini.model", "gemini-2.0-flash"));
        geminiConfig.put("timeout", getProperty("gemini.timeout", "30"));
        geminiConfig.put("debug", getProperty("gemini.debug", "false"));
        geminiConfig.put("apiKey", getEnvironmentOrProperty("GEMINI_API_KEY", "gemini.api.key"));
        return geminiConfig;
    }
    
    public Map<String, String> getClaudeConfig() {
        Map<String, String> claudeConfig = new HashMap<>();
        claudeConfig.put("baseUrl", getProperty("claude.base.url", "https://api.anthropic.com/v1/messages"));
        claudeConfig.put("model", getProperty("claude.model", "claude-3-5-sonnet-20241022"));
        claudeConfig.put("timeout", getProperty("claude.timeout", "30"));
        claudeConfig.put("debug", getProperty("claude.debug", "false"));
        claudeConfig.put("apiKey", getEnvironmentOrProperty("ANTHROPIC_API_KEY", "claude.api.key"));
        return claudeConfig;
    }
    
    public Map<String, String> getOpenAiConfig() {
        Map<String, String> openAiConfig = new HashMap<>();
        openAiConfig.put("baseUrl", getProperty("openai.base.url", "https://api.openai.com/v1/chat/completions"));
        openAiConfig.put("model", getProperty("openai.model", "gpt-4o")); // Modelo sugerido, pode ser alterado
        openAiConfig.put("timeout", getProperty("openai.timeout", "30"));
        openAiConfig.put("debug", getProperty("openai.debug", "true"));
        openAiConfig.put("apiKey", getEnvironmentOrProperty("OPENAI_API_KEY", "openai.api.key"));
        return openAiConfig;
    }
    
    public List<MCPService.ServerConfig> loadServerConfigs() {
        List<MCPService.ServerConfig> configs = new ArrayList<>();
        
        try {
            if (Files.exists(Paths.get(MCP_CONFIG_FILE))) {
                String content = Files.readString(Paths.get(MCP_CONFIG_FILE));
                JsonNode root = objectMapper.readTree(content);
                JsonNode mcpServers = root.get("mcpServers");
                
                if (mcpServers != null) {
                    mcpServers.fields().forEachRemaining(entry -> {
                        String serverName = entry.getKey();
                        JsonNode serverNode = entry.getValue();
                        
                        MCPService.ServerConfig config = new MCPService.ServerConfig();
                        config.name = serverName;
                        config.description = serverNode.get("description").asText("");
                        config.url = serverNode.get("command").asText("");
                        config.type = "stdio"; // Default type
                        config.enabled = serverNode.get("enabled").asBoolean(true);
                        
                        // Set priority
                        int priorityValue = serverNode.get("priority").asInt(1);
                        config.priority = MCPService.ServerConfig.ServerPriority.fromValue(priorityValue);
                        
                        // Process environment variables
                        JsonNode envNode = serverNode.get("env");
                        if (envNode != null && envNode.isObject()) {
                            envNode.fields().forEachRemaining(envEntry -> {
                                config.environment.put(envEntry.getKey(), envEntry.getValue().asText());
                            });
                        }
                        
                        // Process args if needed (currently not used in ServerConfig but could be extended)
                        
                        configs.add(config);
                    });
                    
                    logger.info("📋 Loaded {} MCP servers from: {}", configs.size(), new File(MCP_CONFIG_FILE).getAbsolutePath());
                }
            }
        } catch (Exception e) {
            logger.error("Error loading MCP server configs: {}", e.getMessage());
        }
        
        return configs;
    }
    
    public boolean isLlmConfigValid(String provider) {
        Map<String, String> config = switch (provider.toLowerCase()) {
            case "groq" -> getGroqConfig();
            case "gemini" -> getGeminiConfig();
            case "claude" -> getClaudeConfig();
            case "openai" -> getOpenAiConfig();
            default -> new HashMap<>();
        };
        
        String apiKey = config.get("apiKey");
        boolean isValid = apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("${");
        
        if (!isValid) {
            logger.warn("❌ Invalid configuration for {}: API key not configured", provider);
        }
        
        return isValid;
    }
    
    private String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    private String getEnvironmentOrProperty(String envKey, String propKey) {
        // First try environment variable
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        
        String systemPropValue = System.getProperty(envKey);
        if (systemPropValue != null && !systemPropValue.isEmpty()) {
            return systemPropValue;
        }
        
        // Then try property file
        String propValue = properties.getProperty(propKey);
        if (propValue != null && !propValue.startsWith("${")) {
            return propValue;
        }
        
        return "";
    }
    
    public String getWorkspacePath() {
        return getProperty("filesystem.base.path", "./documents");
    }
    
    public int getMcpTimeout() {
        return Integer.parseInt(getProperty("mcp.timeout", "30"));
    }
    
    public String resolveFilesystemPath() {
        String basePath = getWorkspacePath();
        try {
            Path path = Paths.get(basePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return path.toAbsolutePath().toString();
        } catch (Exception e) {
            logger.warn("Error resolving filesystem path: {}", e.getMessage());
            return basePath;
        }
    }
    
    /**
     * Creates the default application.properties file with all necessary configurations
     */
    private void createDefaultApplicationProperties() throws IOException {
        String defaultContent = """
        		
        		# JavaCLI Application Configuration
				# Application Info
				app.name=JavaCLI
				app.version=0.0.1
				
				# MCP Configuration
				mcp.timeout=30
				mcp.auto.connect=true
				
				# File Paths
				filesystem.base.path=./documents
				
				# Logging
				log.level=INFO
				log.file=${LOG_DIR}/javacli.log
				
				# Gemini Configuration
				gemini.base.url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
				gemini.model=gemini-2.0-flash
				gemini.timeout=30
				gemini.debug=false
				gemini.api.key=
				
				# Groq Configuration
				groq.base.url=https://api.groq.com/openai/v1/chat/completions
				groq.model=llama-3.3-70b-versatile
				groq.timeout=30
				groq.debug=true
				groq.api.key=
				
				# Claude Configuration
				claude.base.url=https://api.anthropic.com/v1/messages
				claude.model=claude-3-5-sonnet-20241022
				claude.timeout=30
				claude.debug=false
				claude.api.key=
				
				# OpenAI Configuration
				openai.base.url=https://api.openai.com/v1/chat/completions
				openai.model=gpt-4o
				openai.timeout=30
				openai.debug=true
				openai.api.key=
								
				# Reflection Configuration
				reflection.max.iterations=3
				reflection.score.threshold=0.8
				reflection.timeout=60
				reflection.debug=true
				""";
        
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            writer.write(defaultContent);
        }
    }
    
    /**
     * Creates the default .mcp.json file with MCP server configurations
     */
    private void createDefaultMcpConfig() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode mcpServers = mapper.createObjectNode();
        
        // GitHub server
        ObjectNode github = mapper.createObjectNode();
        github.put("description", "Integração com GitHub");
        github.put("command", "npx @modelcontextprotocol/server-github");
        github.put("priority", 2);
        github.put("enabled", false);
        ObjectNode githubEnv = mapper.createObjectNode();
        githubEnv.put("GITHUB_PERSONAL_ACCESS_TOKEN", "${GITHUB_TOKEN}");
        githubEnv.put("REQUIRES_NODEJS", "true");
        githubEnv.put("REQUIRES_ENV", "GITHUB_TOKEN");
        github.set("env", githubEnv);
        github.set("args", mapper.createArrayNode());
        
        // Memory server
        ObjectNode memory = mapper.createObjectNode();
        memory.put("description", "Armazenamento temporário em memória");
        memory.put("command", "npx @modelcontextprotocol/server-memory");
        memory.put("priority", 1);
        memory.put("enabled", true);
        ObjectNode memoryEnv = mapper.createObjectNode();
        memoryEnv.put("REQUIRES_NODEJS", "true");
        memory.set("env", memoryEnv);
        memory.set("args", mapper.createArrayNode());
        
        // Weather server
        ObjectNode weather = mapper.createObjectNode();
        weather.put("description", "Previsões meteorológicas via NWS");
        weather.put("command", "npx @h1deya/mcp-server-weather");
        weather.put("priority", 1);
        weather.put("enabled", true);
        ObjectNode weatherEnv = mapper.createObjectNode();
        weatherEnv.put("REQUIRES_NODEJS", "true");
        weatherEnv.put("REQUIRES_ONLINE", "true");
        weather.set("env", weatherEnv);
        weather.set("args", mapper.createArrayNode());
        
        // Time server
        ObjectNode time = mapper.createObjectNode();
        time.put("description", "Servidor para ferramentas de tempo e fuso horário");
        time.put("command", "uvx mcp-server-time");
        time.put("priority", 1);
        time.put("enabled", true);
        ObjectNode timeEnv = mapper.createObjectNode();
        timeEnv.put("REQUIRE_UVX", "true");
        time.set("env", timeEnv);
        
        // Filesystem server
        ObjectNode filesystem = mapper.createObjectNode();
        filesystem.put("description", "Sistema de arquivos - Documents");
        filesystem.put("command", "npx -y @modelcontextprotocol/server-filesystem " + getWorkspacePath());
        filesystem.put("priority", 3);
        filesystem.put("enabled", true);
        ObjectNode filesystemEnv = mapper.createObjectNode();
        filesystemEnv.put("REQUIRES_NODEJS", "true");
        filesystem.set("env", filesystemEnv);
        
        // Add all servers to mcpServers
        mcpServers.set("github", github);
        mcpServers.set("memory", memory);
        mcpServers.set("weather-nws", weather);
        mcpServers.set("filesystem", filesystem);
        mcpServers.set("time", time);
        
        // Add mcpServers to root
        root.set("mcpServers", mcpServers);
        
        // Write to file with pretty printing
        try (FileWriter writer = new FileWriter(MCP_CONFIG_FILE)) {
            writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
    }
    
    /**
     * Configuração programática do logging substituindo logback.xml
     */
    private void setupProgrammaticLogging() {
        if (loggingConfigured) return;
        
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            
            // IMPORTANTE: Limpar configuração anterior para remover console
            loggerContext.reset();
            
            // Criar diretórios necessários
            createLogDirectories();
            
            // Configurar appenders básicos (mantém funcionalidade atual)
            setupBasicAppenders(loggerContext);
            
            // Configurar appenders de conversação
            setupConversationAppenders(loggerContext);
            
            // Configurar root logger SEM console
            ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);
            rootLogger.setAdditive(false); // Impede propagação para console
            
            // Configurar TODOS os loggers para não usar console
            disableConsoleForAllLoggers(loggerContext);
            
            loggingConfigured = true;
            
            // Usar System.out uma única vez para confirmar que console foi desabilitado
            System.out.println("\ud83d\udccb Logging configured - console output disabled");
            
        } catch (Exception e) {
            System.err.println("Failed to setup programmatic logging: " + e.getMessage());
        }
    }
    
    /**
     * Desabilita console para TODOS os loggers existentes e futuros
     */
    private void disableConsoleForAllLoggers(LoggerContext loggerContext) {
        // Desabilitar para todos os pacotes da aplicação
        String[] packages = {
            "com.gazapps",
            "com.gazapps.config", 
            "com.gazapps.config.EnvironmentSetup",
            "com.gazapps.mcp",
            "com.gazapps.llm",
            "com.gazapps.inference",
            "root"
        };
        
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appAppender = 
            (RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>) 
            loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("APPLICATION");
        
        for (String packageName : packages) {
            ch.qos.logback.classic.Logger logger = loggerContext.getLogger(packageName);
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            logger.setAdditive(false); // CRITICAL: Sem propagação
            if (appAppender != null) {
                logger.addAppender(appAppender);
            }
        }
    }
    
    private void createLogDirectories() {
        String[] dirs = {
            "log",
            "log/inference", 
            "log/llm"
        };
        
        for (String dir : dirs) {
            File directory = new File(dir);
            if (!directory.exists()) {
                directory.mkdirs();
                logger.debug("📁 Created log directory: {}", dir);
            }
        }
    }
    
    private void setupBasicAppenders(LoggerContext loggerContext) {
        // 1. Application logs (geral)
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appAppender = 
            setupFileAppender(loggerContext, "APPLICATION", "log/application.log", 
                             "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        
        // 2. Error logs
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> errorAppender = 
            setupFileAppender(loggerContext, "ERROR", "log/errors.log", 
                             "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        
        // Adicionar filtro de erro
        ThresholdFilter errorFilter = new ThresholdFilter();
        errorFilter.setLevel("ERROR");
        errorFilter.start();
        errorAppender.addFilter(errorFilter);
        
        // 3. MCP operations (mantém atual)
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> mcpAppender = 
            setupFileAppender(loggerContext, "MCP", "log/mcp-operations.log", 
                             "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
        
        // Configurar loggers específicos SEM propagação para console
        ch.qos.logback.classic.Logger mcpLogger = loggerContext.getLogger("com.gazapps.mcp");
        mcpLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        mcpLogger.setAdditive(false); // IMPORTANTE: Impede propagação para console
        mcpLogger.addAppender(mcpAppender);
        mcpLogger.addAppender(appAppender); // Também loga no application.log
        
        ch.qos.logback.classic.Logger llmLogger = loggerContext.getLogger("com.gazapps.llm");
        llmLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        llmLogger.setAdditive(false); // IMPORTANTE: Impede propagação para console
        llmLogger.addAppender(appAppender);
        
        ch.qos.logback.classic.Logger inferenceLogger = loggerContext.getLogger("com.gazapps.inference");
        inferenceLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        inferenceLogger.setAdditive(false); // IMPORTANTE: Impede propagação para console
        inferenceLogger.addAppender(appAppender);
        
        ch.qos.logback.classic.Logger configLogger = loggerContext.getLogger("com.gazapps.config");
        configLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        configLogger.setAdditive(false); // IMPORTANTE: Impede propagação para console
        configLogger.addAppender(appAppender);
        
        // Root logger SEM console appender
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appAppender);
        rootLogger.addAppender(errorAppender);
    }
    
    private void setupConversationAppenders(LoggerContext loggerContext) {
        // Buscar appender de aplicação para referência
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appAppender = null;
        
        for (String appenderName : new String[]{"APPLICATION"}) {
            ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            if (rootLogger.getAppender(appenderName) instanceof RollingFileAppender) {
                appAppender = (RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>) rootLogger.getAppender(appenderName);
                break;
            }
        }
        
        // Inference conversation loggers usando configurações do properties
        String[] inferenceTypes = {"react", "reflection", "simple"};
        
        for (String type : inferenceTypes) {
            String enabledKey = "logging.inference." + type + ".conversations.enabled";
            String fileKey = "logging.inference." + type + ".conversations.file";
            String patternKey = "logging.inference." + type + ".conversations.pattern";
            
            if (isLoggingEnabled(enabledKey)) {
                String filename = properties.getProperty(fileKey, "log/inference/" + type + "-conversations.log");
                String pattern = properties.getProperty(patternKey, "%d{HH:mm:ss} %msg%n");
                
                RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = 
                    setupFileAppender(loggerContext, "INFERENCE_CONV_" + type.toUpperCase(), filename, pattern);
                
                // Configurar logger específico
                ch.qos.logback.classic.Logger inferenceLogger = 
                    loggerContext.getLogger("inference.conversation." + type);
                inferenceLogger.setLevel(ch.qos.logback.classic.Level.INFO);
                inferenceLogger.setAdditive(false);
                inferenceLogger.addAppender(appender);
            }
        }
        
        // LLM conversation loggers usando configurações do properties
        String[] llmTypes = {"groq", "claude", "gemini", "openai"};
        
        for (String type : llmTypes) {
            String enabledKey = "logging.llm." + type + ".conversations.enabled";
            String fileKey = "logging.llm." + type + ".conversations.file";
            String patternKey = "logging.llm." + type + ".conversations.pattern";
            
            if (isLoggingEnabled(enabledKey)) {
                String filename = properties.getProperty(fileKey, "log/llm/" + type + "-conversations.log");
                String pattern = properties.getProperty(patternKey, "%d{HH:mm:ss} %msg%n");
                
                RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = 
                    setupFileAppender(loggerContext, "LLM_CONV_" + type.toUpperCase(), filename, pattern);
                
                // Configurar logger específico
                ch.qos.logback.classic.Logger llmLogger = 
                    loggerContext.getLogger("llm.conversation." + type);
                llmLogger.setLevel(ch.qos.logback.classic.Level.INFO);
                llmLogger.setAdditive(false);
                llmLogger.addAppender(appender);
            }
        }
    }
    
    private RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> setupFileAppender(
            LoggerContext loggerContext, String name, String filename, String pattern) {
        
        RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setContext(loggerContext);
        fileAppender.setName(name);
        fileAppender.setFile(filename);
        
        // Configurar rolling policy
        SizeAndTimeBasedRollingPolicy<?> rollingPolicy = new SizeAndTimeBasedRollingPolicy<>();
        rollingPolicy.setContext(loggerContext);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(filename.replace(".log", ".%d{yyyy-MM-dd}.%i.log"));
        rollingPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
        rollingPolicy.setMaxHistory(30);
        rollingPolicy.start();
        
        fileAppender.setRollingPolicy(rollingPolicy);
        
        // Configurar encoder
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern(pattern);
        encoder.start();
        
        fileAppender.setEncoder(encoder);
        fileAppender.start();
        
        // IMPORTANTE: NÃO adicionar automaticamente ao root logger
        // Isso será feito manualmente onde necessário
        
        return fileAppender;
    }
    
    private boolean isLoggingEnabled(String key) {
        return Boolean.parseBoolean(properties.getProperty(key, "true"));
    }
    
    /**
     * Versão menos agressiva - desabilita apenas loggers específicos problemáticos
     */
    public static void disableSpecificNoisyLoggers() {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            
            // NÃO fazer reset - manter configuração padrão
            // Desabilitar APENAS loggers que causam ruído
            String[] noisyLoggers = {
                "com.gazapps.config.EnvironmentSetup"
            };
            
            for (String loggerName : noisyLoggers) {
                ch.qos.logback.classic.Logger logger = loggerContext.getLogger(loggerName);
                logger.setLevel(ch.qos.logback.classic.Level.WARN); // Apenas WARN e ERROR
            }
            
        } catch (Exception e) {
            System.err.println("Failed to configure specific loggers: " + e.getMessage());
        }
    }
    public static void disableConsoleLoggingImmediately() {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();
            
            // Criar diretório se não existir
            File logDir = new File("log");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            // Criar appender para arquivo temporário
            RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> tempAppender = new RollingFileAppender<>();
            tempAppender.setContext(loggerContext);
            tempAppender.setName("TEMP_FILE");
            tempAppender.setFile("log/startup.log");
            
            // Encoder simples
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggerContext);
            encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
            encoder.start();
            
            tempAppender.setEncoder(encoder);
            tempAppender.start();
            
            // Configurar root logger SEM console - IMPORTANTE: NÃO afetar System.out
            ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);
            rootLogger.setAdditive(false); // CRITICAL: Impede que vá para console
            rootLogger.addAppender(tempAppender);
            
            // Desabilitar para todos os pacotes conhecidos
            String[] packages = {
                "com.gazapps",
                "com.gazapps.config",
                "com.gazapps.config.EnvironmentSetup",
                "com.gazapps.mcp",
                "com.gazapps.llm",
                "com.gazapps.inference"
            };
            
            for (String packageName : packages) {
                ch.qos.logback.classic.Logger logger = loggerContext.getLogger(packageName);
                logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
                logger.setAdditive(false); // CRITICAL: Impede propagação para console
                logger.addAppender(tempAppender);
            }
            
            // IMPORTANTE: NÃO interferir com System.out.println para comunicação do usuário
            System.out.println("⚡ Console logging disabled - System.out preserved for user communication");
            
        } catch (Exception e) {
            System.err.println("Failed to disable console logging: " + e.getMessage());
        }
    }
    
    /**
     * Obter logger específico para conversações de inference
     */
    public static Logger getInferenceConversationLogger(String inferenceType) {
        return LoggerFactory.getLogger("inference.conversation." + inferenceType.toLowerCase());
    }
    
    /**
     * Obter logger específico para conversações de LLM
     */
    public static Logger getLlmConversationLogger(String llmType) {
        return LoggerFactory.getLogger("llm.conversation." + llmType.toLowerCase());
    }
}
