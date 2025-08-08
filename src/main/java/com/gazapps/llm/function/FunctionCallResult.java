package com.gazapps.llm.function;

import java.util.Map;

public class FunctionCallResult {
    private String text;
    private String functionName;
    private Map<String, Object> arguments;

    public FunctionCallResult(String text) {
        this.text = text;
    }

    public FunctionCallResult(String functionName, Map<String, Object> arguments) {
        this.functionName = functionName;
        this.arguments = arguments;
    }

    public boolean isFunctionCall() {
        return this.functionName != null;
    }

    public String getText() {
        return text;
    }

    public String getFunctionName() {
        return functionName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
