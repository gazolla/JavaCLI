package com.gazapps.core;

import java.util.ArrayList;
import java.util.List;

public class InMemoryConversationMemory implements ConversationMemory {
    
    private List<Message> messages = new ArrayList<>();
    
    @Override
    public void addMessage(String role, String content) {
        messages.add(new Message(role, content));
    }
    
    @Override
    public List<Message> getRecentMessages() {
        return new ArrayList<>(messages);
    }
}
