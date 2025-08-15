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
import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.exceptions.ErrorMessageHandler;
import com.gazapps.exceptions.InputException;
import com.github.lalyos.jfiglet.FigletFont;


public class App implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    
    private ChatEngine chatEngine;
    private Scanner scanner;
    private CommandProcessor commandProcessor;
    private RuntimeConfigManager configManager;
    private static final Set<String> EXIT_WORDS = Set.of(
            "sair", "exit", "bye", "see you", "quit", "adeus", "tchau", "encerrar", "parar"
        );
    
    public App() throws Exception {
        this.configManager = new RuntimeConfigManager();
        this.chatEngine = ChatEngineBuilder.currentSetup(ChatEngineBuilder.LlmProvider.GROQ, ChatEngineBuilder.InferenceStrategy.TOOLUSE);
        this.commandProcessor = new CommandProcessor(chatEngine, configManager);
        this.scanner = new Scanner(System.in);
    }
    
    public App(ChatEngine chatEngine) {
        this.configManager = new RuntimeConfigManager();
        this.chatEngine = chatEngine;
        this.commandProcessor = new CommandProcessor(chatEngine, configManager);
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
        
        System.exit(0);
    }
    
	public static void main(String[] args) throws Exception {
		Config config = new Config();
		config.createConfigStructure();

		try (App app = new App()) {
			app.showEnhancedBanner();
			app.runChatLoop();
		}
	}
 
    private String handleChatQuery(String query) throws Exception {
        long startTime = System.currentTimeMillis();
        
        try {
            // Basic input validation
            if (query == null || query.trim().isEmpty()) {
                throw new InputException("Message cannot be empty");
            }
            
            if (query.length() > 10000) {
                throw new InputException("Message too long (maximum 10,000 characters)");
            }
            
            String response = chatEngine.processQuery(query);
            long endTime = System.currentTimeMillis();
            
            StringBuilder resposta = new StringBuilder();
            resposta.append("🤖 Assistant: ");
            resposta.append(response);
            resposta.append(String.format(" (⏱️  %.2fs)\n", (endTime - startTime) / 1000.0));
            
            return resposta.toString();
            
        } catch (Exception e) {
            // Use friendly error message handler
            return ErrorMessageHandler.getUserFriendlyMessage(e) + "\n";
        }
    }
    
    private void runChatLoop() {
        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine();
            
            if (EXIT_WORDS.contains(input.toLowerCase())) {
            	System.out.println("Closing chat...");
                break;
            }
            
            if (input.trim().isEmpty()) {
                continue;
            }
            
            try {
                // Check if it's a system command
                if (input.trim().startsWith("/")) {
                    CommandResult result = commandProcessor.processCommand(input);
                    System.out.println(result.getMessage());
                    
                    // If command changed configuration, update chatEngine
                    if (result.hasConfigurationChanged()) {
                        this.chatEngine = result.getNewChatEngine();
                        commandProcessor.updateChatEngine(this.chatEngine);
                    }
                } else {
                    // Normal chat processing
                    String response = handleChatQuery(input);
                    System.out.println(response);
                }
                
                System.out.println();
                
            } catch (Exception e) {
                logger.error("Unexpected error: {}", e.getMessage());
                System.out.println(ErrorMessageHandler.getUserFriendlyMessage(e));
                System.out.println();
            }
        }
        
        logger.info("Chat ended!");
    }
    
    private void showEnhancedBanner() throws IOException {
        String banner = FigletFont.convertOneLine("Java CLI");
        System.out.println(banner);
        
        System.out.println("Starting...");
        
        try {
            String llmProvider = chatEngine.getLLMService().getProviderName();
            String inferenceStrategy = chatEngine.getInference().getClass().getSimpleName();
            
            System.out.printf("✅ %s configurado corretamente%n", llmProvider);
            System.out.println("🔧 MCP servers conectados");
            System.out.printf("🧠 Estratégia: %s%n", inferenceStrategy);
            
        } catch (Exception e) {
            System.out.println("✅ Sistema configurado");
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
