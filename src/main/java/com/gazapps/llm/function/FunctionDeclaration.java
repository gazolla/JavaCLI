package com.gazapps.llm.function;

public class FunctionDeclaration {
    public String name;
    public String description;
    public FunctionParameters parameters;

    public FunctionDeclaration(String name, String description, FunctionParameters parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}
