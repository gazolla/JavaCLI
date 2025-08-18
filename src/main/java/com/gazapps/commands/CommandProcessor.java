package com.gazapps.commands;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Paths;

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
    private static final Set<String> VALID_INFERENCE_STRATEGIES = Set.of("simple", "react", "reflection");
    
    private static final String WORKSPACE_HELP = """
        Available commands:
          /workspace setup    - Configure new workspace
          /workspace check    - Validate current workspace
          /workspace          - Show workspace status""";
    
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
            logger.error("Command processing error", e);
            return CommandResult.error("Error processing command: " + e.getMessage());
        }
    }
    
    private boolean isCommand(String input) {
        return input != null && input.trim().startsWith("/");
    }
    
    private CommandResult handleLlmCommand(String provider) {
        if (isNullOrEmpty(provider)) {
            return CommandResult.error("""
                ❌ Provider parameter required
                Usage: /llm <provider>
                Available providers: groq, gemini, claude, openai""");
        }
        
        provider = provider.trim().toLowerCase();
        
        if (!VALID_LLM_PROVIDERS.contains(provider)) {
            return CommandResult.error(String.format("""
                ❌ Unknown LLM provider '%s'
                Available providers: groq, gemini, claude, openai
                Usage: /llm <provider>""", provider));
        }
        
        try {
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
            
            updateChatEngine(newEngine);
            return createLlmSuccessResponse(provider, llmProvider);
        } catch (Exception e) {
            logger.error("LLM change failed", e);
            return CommandResult.error("❌ Failed to change LLM to " + provider + ": " + e.getMessage());
        }
    }
    
    private CommandResult createLlmSuccessResponse(String provider, ChatEngineBuilder.LlmProvider llmProvider) {
        String providerDisplayName = switch (llmProvider) {
            case GROQ -> "Groq (Llama 3.3 70B Versatile)";
            case GEMINI -> "Gemini (2.0 Flash)";
            case CLAUDE -> "Claude (3.5 Sonnet)";
            case OPENAI -> "OpenAI (GPT-4)";
        };
        
        return CommandResult.success(String.format("""
            ✅ LLM changed to %s
            💾 Conversation history preserved
            ⏱️ Ready""", providerDisplayName), currentChatEngine);
    }
    
    private CommandResult handleInferenceCommand(String strategy) {
        if (isNullOrEmpty(strategy)) {
            return CommandResult.error("""
                ❌ Strategy parameter required
                Usage: /inference <strategy>
                Available strategies: sequential, react, tooluse, reflection""");
        }
        
        strategy = strategy.trim().toLowerCase();
        
        if (!VALID_INFERENCE_STRATEGIES.contains(strategy)) {
            return CommandResult.error(String.format("""
                ❌ Unknown inference strategy '%s'
                Available strategies: sequential, react, tooluse, reflection
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
            
            updateChatEngine(newEngine);
            return createInferenceSuccessResponse(inferenceStrategy);
        } catch (Exception e) {
            logger.error("Inference change failed", e);
            return CommandResult.error("❌ Failed to change inference to " + strategy + ": " + e.getMessage());
        }
    }
    
    private CommandResult createInferenceSuccessResponse(ChatEngineBuilder.InferenceStrategy strategy) {
        String strategyDisplayName = switch (strategy) {
            case SIMPLE -> "Simple";
            case REACT -> "ReAct (Reasoning and Acting)";
            case REFLECTION -> "Reflection (Self-Improvement)";
        };
        
        return CommandResult.success(String.format("""
            ✅ Inference strategy changed to %s
            🧠 Strategy active
            💾 Conversation history preserved
            ⏱️ Ready""", strategyDisplayName), currentChatEngine);
    }
    
    private CommandResult handleConfigCommand() {
        try {
            return CommandResult.success("📊 Current Configuration:\n" + 
                configManager.getCurrentConfigSummary(currentChatEngine));
        } catch (Exception e) {
            logger.error("Failed to get config summary", e);
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
                ⚡ Status: Ready""", llmProvider, inferenceStrategy, memorySize));
        } catch (Exception e) {
            logger.error("Failed to get status", e);
            return CommandResult.error("❌ Failed to retrieve system status");
        }
    }
    
    private CommandResult handleToolsCommand() {
        try {
            if (mcpServers.getConnectedServers().isEmpty()) {
                return CommandResult.success("🔧 Available MCP Tools:\n\n⚠️ No MCP servers currently connected");
            }
            
            StringBuilder toolsList = new StringBuilder("🔧 Available MCP Tools:\n\n");
            mcpServers.getConnectedServers().forEach(server -> {
                McpSyncClient client = mcpServers.getClient(server.name);
                ListToolsResult toolsResult = client.listTools();
                
                toolsList.append("🖥️ Server: ").append(server.name).append("\n");
                toolsResult.tools().forEach(tool -> 
                    toolsList.append("  • ").append(tool.name()).append("\n"));
                toolsList.append("\n");
            });
            
            if (toolsList.toString().equals("🔧 Available MCP Tools:\n\n")) {
                toolsList.append("ℹ️ No tools available on connected servers\n");
            }
            
            toolsList.append("\nUse these tools by asking natural language questions!");
            return CommandResult.success(toolsList.toString());
        } catch (Exception e) {
            logger.error("Failed to list tools", e);
            return CommandResult.error("❌ Failed to retrieve tools list: " + e.getMessage());
        }
    }
    
    private CommandResult handleWorkspaceCommand(String parameter) {
        try {
            if (isNullOrEmpty(parameter)) {
                return showWorkspaceStatus();
            }
            
            return switch (parameter.trim().toLowerCase()) {
                case "setup" -> handleWorkspaceSetup();
                case "check" -> handleWorkspaceCheck();
                default -> CommandResult.error(String.format("❌ Unknown workspace command '%s'\n%s", 
                    parameter, WORKSPACE_HELP));
            };
        } catch (Exception e) {
            logger.error("Workspace command failed", e);
            return CommandResult.error("❌ Failed to process workspace command: " + e.getMessage());
        }
    }
    
    private CommandResult showWorkspaceStatus() {
        StringBuilder status = new StringBuilder("📁 MCP Workspace Status:\n\n");
        String currentPath = EnvironmentSetup.getCurrentWorkspacePath();
        
        if (currentPath == null) {
            status.append("❌ Not configured\n");
        } else {
            String expandedPath = EnvironmentSetup.getExpandedWorkspacePath();
            status.append("✅ Configured: ").append(currentPath).append("\n")
                 .append("📂 Resolved: ").append(expandedPath).append("\n")
                 .append(EnvironmentSetup.isWorkspaceConfigured() ? 
                     "✅ Status: Active and accessible\n" : "❌ Status: Path not accessible\n");
        }
        
        status.append("\n📝 Commands:\n")
             .append("  /workspace setup    - Configure new workspace\n")
             .append("  /workspace check    - Validate current workspace\n")
             .append("  /workspace          - Show this status\n");
        
        return CommandResult.success(status.toString());
    }
    
    private CommandResult handleWorkspaceSetup() {
        try {
            System.out.println("\n🔧 Starting workspace reconfiguration...");
            return EnvironmentSetup.setupWorkspace() ? 
                CommandResult.success("""
                    ✅ Workspace reconfigured successfully!
                    🔄 Please restart JavaCLI for MCP servers to use the new workspace.
                    
                    💡 Why restart? MCP servers load workspace at startup and need
                       to be reinitialized to access the new folder.
                    
                    🚀 Just close and run JavaCLI again - your settings are saved!""") :
                CommandResult.error("❌ Workspace setup cancelled or failed.");
        } catch (Exception e) {
            logger.error("Workspace setup failed", e);
            return CommandResult.error("❌ Workspace setup failed: " + e.getMessage());
        }
    }
    
    private CommandResult handleWorkspaceCheck() {
        try {
            String currentPath = EnvironmentSetup.getCurrentWorkspacePath();
            if (currentPath == null) {
                return CommandResult.success("""
                    🔍 Workspace Validation:
                    
                    ❌ No workspace configured
                    Run '/workspace setup' to configure one.""");
            }
            
            StringBuilder result = new StringBuilder(String.format("""
                🔍 Workspace Validation:
                
                📁 Configured Path: %s
                📂 Expanded Path: %s
                
                """, currentPath, EnvironmentSetup.getExpandedWorkspacePath()));
            
            validateWorkspacePath(result);
            return CommandResult.success(result.toString());
        } catch (Exception e) {
            logger.error("Workspace check failed", e);
            return CommandResult.error("❌ Workspace check failed: " + e.getMessage());
        }
    }
    
    private void validateWorkspacePath(StringBuilder result) {
        try {
            var path = Paths.get(EnvironmentSetup.getExpandedWorkspacePath());
            
            if (!Files.exists(path)) {
                result.append("❌ Directory does not exist\n");
                return;
            }
            
            result.append(Files.isDirectory(path) ? "✅ Is a directory\n" : "❌ Path is not a directory\n")
                 .append(Files.isReadable(path) ? "✅ Directory is readable\n" : "❌ Directory is not readable\n")
                 .append(Files.isWritable(path) ? "✅ Directory is writable\n" : "❌ Directory is not writable\n");
        } catch (Exception e) {
            result.append("❌ Error accessing path: ").append(e.getMessage()).append("\n");
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
                 Strategies: sequential, react, tooluse, reflection
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
    
    private boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    public void updateChatEngine(ChatEngine newChatEngine) {
        this.currentChatEngine = newChatEngine;
    }
}