package com.gazapps.exceptions;

/**
 * Exception for environment and API key configuration problems
 * More specific than the general ConfigException
 */
public class ConfigurationException extends Exception {
    
    private final String provider;
    private final String configType;
    
    public ConfigurationException(String message) {
        super(message);
        this.provider = null;
        this.configType = null;
    }
    
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
        this.provider = null;
        this.configType = null;
    }
    
    public ConfigurationException(String message, String provider, String configType) {
        super(message);
        this.provider = provider;
        this.configType = configType;
    }
    
    public ConfigurationException(String message, String provider, String configType, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.configType = configType;
    }
    
    public String getProvider() {
        return provider;
    }
    
    public String getConfigType() {
        return configType;
    }
    
    /**
     * Get a user-friendly error message
     */
    public String getUserFriendlyMessage() {
        if (provider != null && configType != null) {
            return String.format("Configuration error for %s (%s): %s", provider, configType, getMessage());
        }
        return String.format("Configuration error: %s", getMessage());
    }
}
