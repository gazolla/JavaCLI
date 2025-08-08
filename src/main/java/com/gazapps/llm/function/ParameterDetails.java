package com.gazapps.llm.function;

import java.util.HashMap;
import java.util.Map;

public class ParameterDetails {
    public String type;
    public String description;
    public Map<String, Object> items;

    public ParameterDetails(String type, String description) {
        this.type = type;
        this.description = description;
        
        if ("array".equals(type)) {
            this.items = createDefaultArrayItems();
        } else {
            this.items = null;
        }
    }
    
    public ParameterDetails(String type, String description, Map<String, Object> items) {
        this.type = type;
        this.description = description;
        this.items = items;
    }
    
    private Map<String, Object> createDefaultArrayItems() {
        Map<String, Object> defaultItems = new HashMap<>();
        defaultItems.put("type", "object");
        return defaultItems;
    }
}
