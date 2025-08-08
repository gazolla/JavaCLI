package com.gazapps.inference.react;

import java.util.Map;

public class ActionDecision {
    
    private final boolean shouldAct;
    private final String toolName;
    private final Map<String, Object> args;
    
    private ActionDecision(boolean shouldAct, String toolName, Map<String, Object> args) {
        this.shouldAct = shouldAct;
        this.toolName = toolName;
        this.args = args;
    }
    
    public static ActionDecision noAction() {
        return new ActionDecision(false, null, null);
    }
    
    public static ActionDecision action(String toolName, Map<String, Object> args) {
        return new ActionDecision(true, toolName, args);
    }
    
    public boolean shouldAct() { return shouldAct; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
}
