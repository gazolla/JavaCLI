package com.gazapps.exceptions;

/**
 * Exception for invalid user input
 */
public class InputException extends Exception {
    public InputException(String message) {
        super(message);
    }
}
