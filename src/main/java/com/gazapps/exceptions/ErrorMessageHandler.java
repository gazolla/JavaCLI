package com.gazapps.exceptions;

import com.gazapps.inference.reflection.ReflectionException;

/**
 * Handler for converting technical exceptions to user-friendly messages
 */
public class ErrorMessageHandler {
    
    public static String getUserFriendlyMessage(Exception e) {
        if (e instanceof LLMException) {
            LLMException llmEx = (LLMException) e;
            return String.format("🤖 Issue with %s: %s\n💡 Try again in a few seconds.", 
                                llmEx.getProvider(), 
                                getSimplifiedMessage(e.getMessage()));
        }
        
        if (e instanceof MCPException) {
            MCPException mcpEx = (MCPException) e;
            return String.format("🔧 Issue with tool '%s': %s\n💡 Please check if the service is working.", 
                                mcpEx.getToolName(), 
                                getSimplifiedMessage(e.getMessage()));
        }
        
        if (e instanceof ConfigException) {
            return String.format("⚙️ Configuration issue: %s\n💡 Please check your configuration file.", 
                                getSimplifiedMessage(e.getMessage()));
        }
        
        if (e instanceof InputException) {
            return String.format("📝 Invalid input: %s\n💡 Please correct your message and try again.", 
                                e.getMessage());
        }
        
        if (e instanceof ReflectionException) {
            ReflectionException refEx = (ReflectionException) e;
            return String.format("🧠 Reflection process issue in %s phase: %s\n💡 The system was trying to improve its response quality.", 
                                refEx.getPhase(), 
                                getSimplifiedMessage(refEx.getMessage()));
        }
        
        // Common network exceptions
        if (e instanceof java.net.SocketTimeoutException) {
            return "⏱️ Connection timeout. Please try again in a few seconds.";
        }
        
        if (e instanceof java.net.ConnectException) {
            return "🌐 Connectivity issue. Please check your internet connection.";
        }
        
        if (e instanceof java.io.IOException) {
            return "📡 Communication problem. Please try again.";
        }

        // Fallback for other exceptions
        return String.format("❌ Unexpected error: %s\n💡 Try again or restart the application.", 
                           getSimplifiedMessage(e.getMessage()));
    }
    
    /**
     * Simplifies technical messages for user consumption
     */
    private static String getSimplifiedMessage(String technicalMessage) {
        if (technicalMessage == null || technicalMessage.isEmpty()) {
            return "unknown error";
        }
        
        // Remove stack traces and technical details - take only first line
        String simplified = technicalMessage.split("\n")[0];
        
        // Replace technical terms with more user-friendly ones
        simplified = simplified.replace("HTTP", "connection")
                              .replace("JSON", "data")
                              .replace("null pointer", "missing value")
                              .replace("timeout", "time limit exceeded")
                              .replace("connection refused", "service unavailable");
        
        return simplified.toLowerCase();
    }
}
