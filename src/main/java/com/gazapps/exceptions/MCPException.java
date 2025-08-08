package com.gazapps.exceptions;

/**
 * Exception for MCP server related problems
 */
public class MCPException extends Exception {
    private final String serverName;
    private final String toolName;
    
    public MCPException(String message, String serverName, String toolName) {
        super(message);
        this.serverName = serverName;
        this.toolName = toolName;
    }
    
    public MCPException(String message, String serverName, String toolName, Throwable cause) {
        super(message, cause);
        this.serverName = serverName;
        this.toolName = toolName;
    }
    
    public String getServerName() { 
        return serverName; 
    }
    
    public String getToolName() { 
        return toolName; 
    }
}
