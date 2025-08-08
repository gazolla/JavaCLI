package com.gazapps.exceptions;

/**
 * Exception for configuration related problems
 */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }
    
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
