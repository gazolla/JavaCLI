package com.gazapps.commands;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.config.EnvironmentSetup;
import com.gazapps.config.RuntimeConfigManager;
import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.mcp.MCPServers;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

public class CommandProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessor.class);
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^/(\\w+)(?:\\s+(.+))?$");
    
    private static final Set<String> VALID_LLM_PROVIDERS = Set.of("groq", "gemini", "claude", "openai");
    private static final Set<String> VALID_INFERENCE_STRATEGIES = Set.of("sequential", "react", "tooluse");
    
    private ChatEngine currentChatEngine;
    private final RuntimeConfigManager configManager;
    private MCPServers mcpServers;
    
    public CommandProcessor(ChatEngine chatEngine, RuntimeConfigManager configManager, MCPServers mcpServers) {
    	this(chatEngine, configManager);
        this.mcpServers = mcpServers;
    }
    
    public CommandProcessor(ChatEngine chatEngine, RuntimeConfigManager configManager) {
        this.currentChatEngine = chatEngine;
        this.configManager = configManager;
    }
    
     
    public CommandResult processCommand(String input) {
        try {
            if (!isCommand(input)) {
                return CommandResult.error("Not a valid command format");
            }
            
            Matcher matcher = COMMAND_PATTERN.matcher(input.trim());
            if (!matcher.matches()) {
                return CommandResult.error("Invalid command syntax");
            }
            
            String command = matcher.group(1).toLowerCase();
            String parameter = matcher.group(2);
            
            return switch (command) {
                case "llm" -> handleLlmCommand(parameter);
                case "inference" -> handleInferenceCommand(parameter);
                case "config" -> handleConfigCommand();
                case "help" -> handleHelpCommand();
                case "status" -> handleStatusCommand();
                case "tools" -> handleToolsCommand();
                case "workspace" -> handleWorkspaceCommand(parameter);
                default -> CommandResult.error("Unknown command: " + command + "\nType /help for available commands");
            };
            
        } catch (Exception e) {
            logger.error("Command processing error: {}", e.getMessage(), e);
            return CommandResult.error("Error processing command: " + e.getMessage());
        }
    }
    
    private boolean isCommand(String input) {
        return input != null && input.trim().startsWith("/");
    }
    
    private CommandResult handleLlmCommand(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return CommandResult.error("""
                ❌ Provider parameter required
                Usage: /llm <provider>
                Available providers: groq, gemini, claude, openai""");
        }
        
        provider = provider.trim().toLowerCase();
        
        if (!isValidProvider(provider)) {
            return CommandResult.error(String.format("""
                ❌ Unknown LLM provider '%s'
                Available providers: groq, gemini, claude, openai
                Usage: /llm <provider>""", provider));
        }
        
        try {
            // Check if provider is configured
            if (!EnvironmentSetup.isProviderConfigured(provider)) {
                logger.info("Provider {} not configured, offering inline setup", provider);
                
                if (!EnvironmentSetup.setupProviderInline(provider)) {
				    return CommandResult.error("❌ Configuration cancelled. Cannot switch to " + provider + ".");
				}
            }
            
            logger.info("Attempting to change LLM to: {}", provider);
            
            ChatEngineBuilder.LlmProvider llmProvider = ChatEngineBuilder.LlmProvider.valueOf(provider.toUpperCase());
            ChatEngine newEngine = configManager.recreateChatEngineWithNewLlm(currentChatEngine, llmProvider);
            
            if (!configManager.validateConfiguration(newEngine)) {
                return CommandResult.error("❌ Failed to configure " + provider + ". Please check API key and connectivity.");
            }
            
            this.currentChatEngine = newEngine;
            String providerDisplayName = getProviderDisplayName(llmProvider);
            
            logger.info("Successfully changed LLM to: {}", provider);
            return CommandResult.success(String.format("""
                ✅ LLM changed to %s
                💾 Conversation history preserved
                ⏱️ Ready""", providerDisplayName), newEngine);
            
        } catch (Exception e) {
            logger.error("LLM change failed: {}", e.getMessage(), e);
            return CommandResult.error("❌ Failed to change LLM to " + provider + ": " + e.getMessage());
        }
    }
    
    private CommandResult handleInferenceCommand(String strategy) {
        if (strategy == null || strategy.trim().isEmpty()) {
            return CommandResult.error("""
                ❌ Strategy parameter required
                Usage: /inference <strategy>
                Available strategies: sequential, react, tooluse""");
        }
        
        strategy = strategy.trim().toLowerCase();
        
        if (!isValidStrategy(strategy)) {
            return CommandResult.error(String.format("""
                ❌ Unknown inference strategy '%s'
                Available strategies: sequential, react, tooluse
                Usage: /inference <strategy>""", strategy));
        }
        
        try {
            logger.info("Attempting to change inference to: {}", strategy);
            
            ChatEngineBuilder.InferenceStrategy inferenceStrategy = 
                ChatEngineBuilder.InferenceStrategy.valueOf(strategy.toUpperCase());
            
            ChatEngine newEngine = configManager.recreateChatEngineWithNewInference(currentChatEngine, inferenceStrategy);
            
            if (!configManager.validateConfiguration(newEngine)) {
                return CommandResult.error("❌ Failed to configure " + strategy + " inference strategy.");
            }
            
            this.currentChatEngine = newEngine;
            String strategyDisplayName = getStrategyDisplayName(inferenceStrategy);
            
            logger.info("Successfully changed inference to: {}", strategy);
            return CommandResult.success(String.format("""
                ✅ Inference strategy changed to %s
                🧠 Strategy active
                💾 Conversation history preserved
                ⏱️ Ready""", strategyDisplayName), newEngine);
            
        } catch (Exception e) {
            logger.error("Inference change failed: {}", e.getMessage(), e);
            return CommandResult.error("❌ Failed to change inference to " + strategy + ": " + e.getMessage());
        }
    }
    
    private CommandResult handleConfigCommand() {
        try {
            String configSummary = configManager.getCurrentConfigSummary(currentChatEngine);
            return CommandResult.success("📊 Current Configuration:\n" + configSummary);
        } catch (Exception e) {
            logger.error("Failed to get config summary: {}", e.getMessage());
            return CommandResult.error("❌ Failed to retrieve configuration");
        }
    }
    
    private CommandResult handleStatusCommand() {
        try {
            String llmProvider = currentChatEngine.getLLMService().getProviderName();
            String inferenceStrategy = currentChatEngine.getInference().getClass().getSimpleName();
            int memorySize = currentChatEngine.getMemory().getRecentMessages().size();
            
            return CommandResult.success(String.format("""
                📊 System Status:
                🤖 LLM: %s
                🧠 Inference: %s
                💾 Memory: %d messages
                🔧 MCP Tools: Available
                ⚡ Status: Ready""", 
                llmProvider, inferenceStrategy, memorySize));
                
        } catch (Exception e) {
            logger.error("Failed to get status: {}", e.getMessage());
            return CommandResult.error("❌ Failed to retrieve system status");
        }
    }
    
    private CommandResult handleToolsCommand() {
        try {
            StringBuilder toolsList = new StringBuilder();
            toolsList.append("🔧 Available MCP Tools:\n\n");
            
            this.mcpServers.getConnectedServers().forEach(server -> {
                McpSyncClient client = mcpServers.getClient(server.name);
                ListToolsResult toolsResult = client.listTools();
                
                toolsList.append("🖥️ Server: ").append(server.name).append("\n");
                
                toolsResult.tools().forEach(tool -> {
                    toolsList.append("  • ").append(tool.name())
                           // .append(" - ").append(tool.description())
                            .append("\n");
                });
                
                toolsList.append("\n");
                logger.info("Retrieved tools from MCP server: {}", server.name);
            });
            
            if (this.mcpServers.getConnectedServers().isEmpty()) {
                toolsList.append("⚠️ No MCP servers currently connected\n");
            } else if (toolsList.toString().equals("🔧 Available MCP Tools:\n\n")) {
                toolsList.append("ℹ️ No tools available on connected servers\n");
            }
            
            toolsList.append("\nUse these tools by asking natural language questions!");
            
            return CommandResult.success(toolsList.toString());
            
        } catch (Exception e) {
            logger.error("Failed to list tools: {}", e.getMessage(), e);
            return CommandResult.error("❌ Failed to retrieve tools list: " + e.getMessage());
        }
    }
    
    private CommandResult handleWorkspaceCommand(String parameter) {
        try {
            // If no parameter, show current workspace status
            if (parameter == null || parameter.trim().isEmpty()) {
                StringBuilder status = new StringBuilder();
                status.append("📁 MCP Workspace Status:\n\n");
                
                String currentPath = EnvironmentSetup.getCurrentWorkspacePath();
                if (currentPath != null) {
                    String expandedPath = EnvironmentSetup.getExpandedWorkspacePath();
                    status.append("✅ Configured: ").append(currentPath).append("\n");
                    status.append("📂 Resolved: ").append(expandedPath).append("\n");
                    
                    if (EnvironmentSetup.isWorkspaceConfigured()) {
                        status.append("✅ Status: Active and accessible\n");
                    } else {
                        status.append("❌ Status: Path not accessible\n");
                    }
                } else {
                    status.append("❌ Not configured\n");
                }
                
                status.append("\n📝 Commands:\n");
                status.append("  /workspace setup    - Configure new workspace\n");
                status.append("  /workspace check    - Validate current workspace\n");
                status.append("  /workspace          - Show this status\n");
                
                return CommandResult.success(status.toString());
            }
            
            // Handle subcommands
            String subcommand = parameter.trim().toLowerCase();
            
            switch (subcommand) {
                case "setup":
                    return handleWorkspaceSetup();
                case "check":
                    return handleWorkspaceCheck();
                default:
                    return CommandResult.error(String.format("""
                        ❌ Unknown workspace command '%s'
                        Available commands:
                          /workspace setup    - Configure new workspace
                          /workspace check    - Validate current workspace
                          /workspace          - Show workspace status""", subcommand));
            }
            
        } catch (Exception e) {
            logger.error("Workspace command failed: {}", e.getMessage(), e);
            return CommandResult.error("❌ Failed to process workspace command: " + e.getMessage());
        }
    }
    
    private CommandResult handleWorkspaceSetup() {
        try {
            System.out.println("\n🔧 Starting workspace reconfiguration...");
            
            if (EnvironmentSetup.setupWorkspace()) {
                return CommandResult.success("""
                    ✅ Workspace reconfigured successfully!
                    🔄 Please restart JavaCLI for MCP servers to use the new workspace.
                    
                    💡 Why restart? MCP servers load workspace at startup and need
                       to be reinitialized to access the new folder.
                    
                    🚀 Just close and run JavaCLI again - your settings are saved!""");
            } else {
                return CommandResult.error("❌ Workspace setup cancelled or failed.");
            }
            
        } catch (Exception e) {
            logger.error("Workspace setup failed: {}", e.getMessage(), e);
            return CommandResult.error("❌ Workspace setup failed: " + e.getMessage());
        }
    }
    
    private CommandResult handleWorkspaceCheck() {
        try {
            StringBuilder result = new StringBuilder();
            result.append("🔍 Workspace Validation:\n\n");
            
            String currentPath = EnvironmentSetup.getCurrentWorkspacePath();
            if (currentPath == null) {
                result.append("❌ No workspace configured\n");
                result.append("Run '/workspace setup' to configure one.\n");
                return CommandResult.success(result.toString());
            }
            
            result.append("📁 Configured Path: ").append(currentPath).append("\n");
            
            String expandedPath = EnvironmentSetup.getExpandedWorkspacePath();
            result.append("📂 Expanded Path: ").append(expandedPath).append("\n\n");
            
            // Check if workspace is properly configured
            if (EnvironmentSetup.isWorkspaceConfigured()) {
                result.append("✅ Workspace is properly configured and accessible\n");
                
                // Additional validation
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(expandedPath);
                    if (java.nio.file.Files.exists(path)) {
                        result.append("✅ Directory exists\n");
                    }
                    if (java.nio.file.Files.isReadable(path)) {
                        result.append("✅ Directory is readable\n");
                    }
                    if (java.nio.file.Files.isWritable(path)) {
                        result.append("✅ Directory is writable\n");
                    }
                } catch (Exception e) {
                    result.append("❌ Error checking directory: ").append(e.getMessage()).append("\n");
                }
                
            } else {
                result.append("❌ Workspace validation failed\n");
                result.append("Issues found:\n");
                
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(expandedPath);
                    if (!java.nio.file.Files.exists(path)) {
                        result.append("  ❌ Directory does not exist\n");
                    }
                    if (!java.nio.file.Files.isDirectory(path)) {
                        result.append("  ❌ Path is not a directory\n");
                    }
                    if (!java.nio.file.Files.isReadable(path)) {
                        result.append("  ❌ Directory is not readable\n");
                    }
                    if (!java.nio.file.Files.isWritable(path)) {
                        result.append("  ❌ Directory is not writable\n");
                    }
                } catch (Exception e) {
                    result.append("  ❌ Error accessing path: ").append(e.getMessage()).append("\n");
                }
                
                result.append("\nRun '/workspace setup' to reconfigure.\n");
            }
            
            return CommandResult.success(result.toString());
            
        } catch (Exception e) {
            logger.error("Workspace check failed: {}", e.getMessage(), e);
            return CommandResult.error("❌ Workspace check failed: " + e.getMessage());
        }
    }
    
    private CommandResult handleHelpCommand() {
        return CommandResult.success("""
            🤖 JavaCLI Help
            
            💬 Chat Commands:
               Just type your message and press Enter
            
            🔧 Configuration Commands:
               /llm <provider>        - Change LLM provider
                 Providers: groq, gemini, claude, openai
                 Example: /llm groq
               
               /inference <strategy>  - Change inference strategy  
                 Strategies: sequential, react, tooluse
                 Example: /inference react
               
               /workspace [command]   - Manage MCP workspace
                 Commands: setup, check
                 Example: /workspace setup
               
               /config               - Show current configuration
               /help                 - Show this help
            
            📊 Status Commands:
               /status               - System status and performance
               /tools                - List available MCP tools
               /workspace            - Show workspace status
            
            🚪 Exit Commands:
               exit, quit, bye, sair, tchau
            
            💡 Tips:
               - Be specific in your requests
               - Use tools for file operations, weather, time, etc.
               - Configuration changes preserve your conversation history
               - MCP filesystem server uses your configured workspace
            
            Example session:
               You: /llm gemini
               🤖: ✅ LLM changed to Gemini (2.0 Flash)
               
               You: /workspace setup
               🤖: [Interactive workspace configuration]
               
               You: What's the weather like?
               🤖: [Uses weather tools to get forecast]""");
    }
    
    private boolean isValidProvider(String provider) {
        return VALID_LLM_PROVIDERS.contains(provider);
    }
    
    private boolean isValidStrategy(String strategy) {
        return VALID_INFERENCE_STRATEGIES.contains(strategy);
    }
    
    private String getProviderDisplayName(ChatEngineBuilder.LlmProvider provider) {
        return switch (provider) {
            case GROQ -> "Groq (Llama 3.3 70B Versatile)";
            case GEMINI -> "Gemini (2.0 Flash)";
            case CLAUDE -> "Claude (3.5 Sonnet)";
            case OPENAI -> "OpenAI (GPT-4)";
        };
    }
    
    private String getStrategyDisplayName(ChatEngineBuilder.InferenceStrategy strategy) {
        return switch (strategy) {
            case SEQUENTIAL -> "Sequential";
            case REACT -> "ReAct (Reasoning and Acting)";
            case TOOLUSE -> "Tool Use";
        };
    }
    
    public void updateChatEngine(ChatEngine newChatEngine) {
        this.currentChatEngine = newChatEngine;
    }
}
