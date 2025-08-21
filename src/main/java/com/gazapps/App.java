package com.gazapps;

import java.io.IOException;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.commands.CommandProcessor;
import com.gazapps.commands.CommandResult;
import com.gazapps.config.Config;
import com.gazapps.config.RuntimeConfigManager;
import com.gazapps.config.EnvironmentSetup;
import com.gazapps.exceptions.ConfigurationException;
import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.core.ChatEngineBuilder.InferenceStrategy;
import com.gazapps.core.ChatEngineBuilder.LlmProvider;
import com.gazapps.exceptions.ErrorMessageHandler;
import com.gazapps.exceptions.InputException;
import com.github.lalyos.jfiglet.FigletFont;

public class App implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private static final Set<String> EXIT_WORDS = Set.of(
        "sair", "exit", "bye", "see you", "quit", "adeus", "tchau", "encerrar", "parar"
    );
    private static final int MAX_INPUT_LENGTH = 10000;
    private static final String ASSISTANT_PREFIX = "🤖 Assistant: ";
    private static final String TIME_FORMAT = " (⏱️  %.2fs)\n";
    
    private final LlmProvider llm = ChatEngineBuilder.LlmProvider.GROQ;
    private final InferenceStrategy inference = ChatEngineBuilder.InferenceStrategy.REACT;
    
    private ChatEngine chatEngine;
    private Scanner scanner;
    private CommandProcessor commandProcessor;
    private RuntimeConfigManager configManager;

    public App() throws Exception {
        // Criar LLM primeiro
        com.gazapps.llm.Llm llmService = com.gazapps.llm.LlmBuilder.groq(null);
        initialize(ChatEngineBuilder.currentSetup(llm, inference, llmService));
    }
    
    public App(ChatEngine chatEngine) {
        initialize(chatEngine);
    }
    
    private void initialize(ChatEngine chatEngine) {
        this.configManager = new RuntimeConfigManager(llm.toString(), inference.toString());
        this.chatEngine = chatEngine;
        this.commandProcessor = new CommandProcessor(chatEngine, configManager, chatEngine.getMcpServers());
        this.scanner = new Scanner(System.in);
    }
    
    @Override
    public void close() {
        if (scanner != null) {
            scanner.close();
        }
        
        if (chatEngine != null) {
            chatEngine.close();
        }
        
        EnvironmentSetup.cleanup();
        System.exit(0);
    }
    
    public static void main(String[] args) throws Exception {
        // TEMPORARIAMENTE COMENTADO: Configuração muito agressiva interfere com System.out
        // Config.disableConsoleLoggingImmediately();
        
        starting();
        
        if (!EnvironmentSetup.ensureApiKeysConfigured()) {
            throw new ConfigurationException("Application cannot start without API key configuration");
        }
        
        new Config().createConfigStructure();

        try (var app = new App()) {
            app.showSystemStarted();
            app.runChatLoop();
        }
    }

    private static void starting() throws IOException {
        System.out.println(FigletFont.convertOneLine("Java CLI"));
        System.out.println("Starting...");
    }
 
    private String handleChatQuery(String query) {
        long startTime = System.currentTimeMillis();
       
        try {
        	validateInput(query);
            String response = chatEngine.processQuery(query);
            return formatResponse(response, startTime);
        } catch (Exception e) {
            return ErrorMessageHandler.getUserFriendlyMessage(e) + "\n";
        }
    }
    
    private void validateInput(String query) throws InputException {
        if (query == null || query.trim().isEmpty()) {
            throw new InputException("Message cannot be empty");
        }
        if (query.length() > MAX_INPUT_LENGTH) {
            throw new InputException("Message too long (maximum 10,000 characters)");
        }
    }
    
    private String formatResponse(String response, long startTime) {
        long endTime = System.currentTimeMillis();
        return ASSISTANT_PREFIX 
            + response 
            + String.format(TIME_FORMAT, (endTime - startTime) / 1000.0);
    }
    
    private void runChatLoop() {
        while (true) {
            System.out.print("You: ");
            var input = scanner.nextLine();
            
            if (shouldExit(input)) {
                System.out.println("Closing chat...");
                break;
            }
            
            if (input.trim().isEmpty()) {
                continue;
            }
            
            processInput(input);
        }
        logger.info("Chat ended!");
    }
    
    private boolean shouldExit(String input) {
        return EXIT_WORDS.contains(input.toLowerCase());
    }
    
    private void processInput(String input) {
        try {
            if (input.trim().startsWith("/")) {
                processCommand(input);
            } else {
                System.out.println(handleChatQuery(input));
            }
            System.out.println();
        } catch (Exception e) {
            handleProcessingError(e);
        }
    }
    
    private void processCommand(String input) {
        var result = commandProcessor.processCommand(input);
        System.out.println(result.getMessage());
        
        if (result.hasConfigurationChanged()) {
            updateChatEngine(result);
        }
    }
    
    private void updateChatEngine(CommandResult result) {
        this.chatEngine = result.getNewChatEngine();
        commandProcessor.updateChatEngine(this.chatEngine);
    }
    
    private void handleProcessingError(Exception e) {
        logger.error("Unexpected error: {}", e.getMessage());
        System.out.println(ErrorMessageHandler.getUserFriendlyMessage(e));
        System.out.println();
    }
    
    private void showSystemStarted() {
        try {
            var llmProvider = chatEngine.getLLMService().getProviderName();
            var inferenceStrategy = chatEngine.getInference().getClass().getSimpleName();
            var connectedServers = chatEngine.getMcpServers().getConnectedServers().size();
            
            System.out.printf("✅ %s configured%n", llmProvider);
            System.out.printf(connectedServers > 0 
                ? "🔧 %d MCP servers connected%n" 
                : "⚠️ No MCP servers connected%n", connectedServers);
            System.out.printf("🧠 Strategy: %s%n", inferenceStrategy);
        } catch (Exception e) {
            System.out.println("Something went wrong...");
        }
        
        System.out.println("""
                
                Tips for getting started:
                1. Ask questions, edit files, or run commands.
                2. Be specific for the best results.
                3. /help for more information.
                4. /llm <provider> or /inference <strategy> to change configuration.
                """);
    }
}