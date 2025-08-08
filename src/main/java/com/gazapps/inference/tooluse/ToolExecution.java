package com.gazapps.inference.tooluse;

import java.util.Map;

public class ToolExecution {
    
    private final String toolName;
    private final Map<String, Object> arguments;
    private final String result;
    private final boolean success;
    private final String error;
    private final long executionTimeMs;
    
    private ToolExecution(String toolName, Map<String, Object> arguments, String result, 
                         boolean success, String error, long executionTimeMs) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.success = success;
        this.error = error;
        this.executionTimeMs = executionTimeMs;
    }
    
    public static ToolExecution success(String toolName, Map<String, Object> arguments, 
                                       String result, long executionTimeMs) {
        return new ToolExecution(toolName, arguments, result, true, null, executionTimeMs);
    }
    
    public static ToolExecution failure(String toolName, Map<String, Object> arguments, 
                                       String error, long executionTimeMs) {
        return new ToolExecution(toolName, arguments, null, false, error, executionTimeMs);
    }
    
    public String getToolName() { return toolName; }
    public Map<String, Object> getArguments() { return arguments; }
    public String getResult() { return result; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    
    @Override
    public String toString() {
        if (success) {
            return String.format("ToolExecution{tool='%s', success=true, time=%dms}", 
                               toolName, executionTimeMs);
        } else {
            return String.format("ToolExecution{tool='%s', success=false, error='%s', time=%dms}", 
                               toolName, error, executionTimeMs);
        }
    }
}
