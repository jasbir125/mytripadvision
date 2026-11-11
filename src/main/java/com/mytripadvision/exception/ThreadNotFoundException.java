package com.mytripadvision.exception;

public class ThreadNotFoundException extends RuntimeException {

    public ThreadNotFoundException() {
        super("Thread not found.");
    }
}
