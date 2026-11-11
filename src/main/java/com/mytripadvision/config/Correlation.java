package com.mytripadvision.config;

import java.util.regex.Pattern;

public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_MDC_KEY = "requestId";

    /** Accept only safe values from clients (prevents log injection and unbounded header values). */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private Correlation() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}
