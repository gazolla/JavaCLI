package com.gazapps;

import java.io.IOException;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.config.Config;
import com.gazapps.core.ChatEngine;
import com.gazapps.core.ChatEngineBuilder;
import com.gazapps.exceptions.ErrorMessageHandler;
import com.gazapps.exceptions.InputException;
import com.github.lalyos.jfiglet.FigletFont;


public class App implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    
    private ChatEngine chatEngine;
    private Scanner scanner;
    private static final Set<String> EXIT_WORDS = Set.of(
            "sair", "exit", "bye", "see you", "quit", "adeus", "tchau", "encerrar", "parar"
        );
    
    public App() throws Exception {
        this.chatEngine = ChatEngineBuilder.currentSetup(ChatEngineBuilder.LlmProvider.GROQ, ChatEngineBuilder.InferenceStrategy.TOOLUSE);
        this.scanner = new Scanner(System.in);
    }
    
    public App(ChatEngine chatEngine) {
        this.chatEngine = chatEngine;
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

		printBanner();

		try (App app = new App()) {
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
        System.out.println("Type 'exit' to leave chat");
        System.out.println("========================");
        
        while (true) {
            System.out.print("You: ");
            String mensagem = scanner.nextLine();
            
            if (EXIT_WORDS.contains(mensagem.toLowerCase())) {
            	System.out.println("closing chat...");
                break;
            }
            
            if (mensagem.trim().isEmpty()) {
                continue;
            }
            
            try {
                String resposta = handleChatQuery(mensagem);
                System.out.println(resposta);
                System.out.println(); 
            } catch (Exception e) {
                // handleChatQuery now handles all exceptions internally
                logger.error("Unexpected error: {}", e.getMessage());
                System.out.println(ErrorMessageHandler.getUserFriendlyMessage(e));
                System.out.println();
            }
        }
        
        logger.info("Chat ended!");
    }
    
    public static void printBanner() throws IOException {
        String banner = FigletFont.convertOneLine("Java CLI");
            
        System.out.println(banner);
        System.out.println("Starting...");
    }
}
