package com.mytripadvision.exception;

import lombok.Getter;

/** A remote dependency failed. The reason is already scrubbed of keys and URLs; it never carries a cause. */
@Getter
public class ExternalServiceException extends RuntimeException {

    private final String service;

    public ExternalServiceException(String service, String reason) {
        super(service + ": " + reason);
        this.service = service;
    }
}
