package com.gazapps.commands;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.config.RuntimeConfigManager;
import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;

public class CommandProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessor.class);
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^/(\\w+)(?:\\s+(.+))?$");
    
    private static final Set<String> VALID_LLM_PROVIDERS = Set.of("groq", "gemini", "claude", "openai");
    private static final Set<String> VALID_INFERENCE_STRATEGIES = Set.of("sequential", "react", "tooluse");
    
    private ChatEngine currentChatEngine;
    private final RuntimeConfigManager configManager;
    
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
            // This would need to be implemented to list MCP tools
            return CommandResult.success("""
                🔧 Available MCP Tools:
                📁 Filesystem operations (read, write, list, search)
                🌤️ Weather forecasts and alerts
                📰 RSS feed parsing
                🕐 Date/time operations
                💾 Memory/knowledge graph operations
                
                Use these tools by asking natural language questions!""");
                
        } catch (Exception e) {
            logger.error("Failed to list tools: {}", e.getMessage());
            return CommandResult.error("❌ Failed to retrieve tools list");
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
               
               /config               - Show current configuration
               /help                 - Show this help
            
            📊 Status Commands:
               /status               - System status and performance
               /tools                - List available MCP tools
            
            🚪 Exit Commands:
               exit, quit, bye, sair, tchau
            
            💡 Tips:
               - Be specific in your requests
               - Use tools for file operations, weather, time, etc.
               - Configuration changes preserve your conversation history
            
            Example session:
               You: /llm gemini
               🤖: ✅ LLM changed to Gemini (2.0 Flash)
               
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
