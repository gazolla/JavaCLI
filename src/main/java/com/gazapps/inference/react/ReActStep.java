package com.gazapps.inference.react;

import java.util.Map;

public class ReActStep {
    
    public enum StepType { THOUGHT, ACTION, OBSERVATION }
    
    private final StepType type;
    private final String content;
    private final String toolName;
    private final Map<String, Object> toolArgs;
    
    private ReActStep(StepType type, String content, String toolName, Map<String, Object> toolArgs) {
        this.type = type;
        this.content = content;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
    }
    
    public static ReActStep thought(String thinking) {
        return new ReActStep(StepType.THOUGHT, thinking, null, null);
    }
    
    public static ReActStep action(String toolName, Map<String, Object> args) {
        return new ReActStep(StepType.ACTION, toolName, toolName, args);
    }
    
    public static ReActStep observation(String result) {
        return new ReActStep(StepType.OBSERVATION, result, null, null);
    }
    
    public StepType getType() { return type; }
    public String getContent() { return content; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getToolArgs() { return toolArgs; }
    
    @Override
    public String toString() {
        return type + ": " + content;
    }
}
