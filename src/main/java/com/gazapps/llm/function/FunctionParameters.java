package com.gazapps.llm.function;

import java.util.Map;

public class FunctionParameters {
    public String type;
    public Map<String, ParameterDetails> properties;
    public Object required;

    public FunctionParameters(String type, Map<String, ParameterDetails> properties, Object required) {
        this.type = type;
        this.properties = properties;
        this.required = required;
    }
}
