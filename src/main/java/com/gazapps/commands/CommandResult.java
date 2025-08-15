package com.gazapps.commands;

import com.gazapps.core.ChatEngine;

public class CommandResult {
    private final boolean success;
    private final String message;
    private final ChatEngine newChatEngine;
    
    private CommandResult(boolean success, String message, ChatEngine newChatEngine) {
        this.success = success;
        this.message = message;
        this.newChatEngine = newChatEngine;
    }
    
    public static CommandResult success(String message) {
        return new CommandResult(true, message, null);
    }
    
    public static CommandResult success(String message, ChatEngine newEngine) {
        return new CommandResult(true, message, newEngine);
    }
    
    public static CommandResult error(String message) {
        return new CommandResult(false, message, null);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public ChatEngine getNewChatEngine() {
        return newChatEngine;
    }
    
    public boolean hasConfigurationChanged() {
        return newChatEngine != null;
    }
}
