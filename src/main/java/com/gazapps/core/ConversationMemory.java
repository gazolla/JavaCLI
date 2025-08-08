package com.gazapps.core;

import java.util.List;

public interface ConversationMemory {
    
    void addMessage(String role, String content);
    
    List<Message> getRecentMessages();
}
