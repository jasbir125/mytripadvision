package com.mytripadvision.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Turns a {@code postgresql://user:pass@host/db} URL (the Render style used by the Python app) into JDBC settings. */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /**
     * sslmode=require is added when missing, except for local hosts, so a plain local database still works.
     * A value that already starts with {@code jdbc:} is used unchanged.
     */
    public static DatabaseUrl parse(String value) {
        String url = value.trim();
        if (url.startsWith("jdbc:")) {
            return new DatabaseUrl(url, null, null);
        }
        URI uri = URI.create(url.replaceFirst("^postgres(ql)?://", "postgresql://"));
        if (uri.getHost() == null || !"postgresql".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must look like postgresql://user:password@host:port/database");
        }
        String username = null;
        String password = null;
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null) {
            int colon = rawUserInfo.indexOf(':');
            username = decode(colon < 0 ? rawUserInfo : rawUserInfo.substring(0, colon));
            password = colon < 0 ? null : decode(rawUserInfo.substring(colon + 1));
        }
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null) {
            jdbc.append('?').append(query);
        }
        if ((query == null || !query.contains("sslmode=")) && !LOCAL_HOSTS.contains(uri.getHost().toLowerCase())) {
            jdbc.append(query == null ? '?' : '&').append("sslmode=require");
        }
        return new DatabaseUrl(jdbc.toString(), username, password);
    }

    private static String decode(String part) {
        return URLDecoder.decode(part, StandardCharsets.UTF_8);
    }
}
