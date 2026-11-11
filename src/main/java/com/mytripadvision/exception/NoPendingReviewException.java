package com.mytripadvision.exception;

/** Nothing is paused on this thread (never was, or it was already approved). */
public class NoPendingReviewException extends RuntimeException {

    public NoPendingReviewException() {
        super("No plan is waiting for review on this chat (it may already be approved).");
    }
}
