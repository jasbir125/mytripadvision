package com.mytripadvision.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** When DATABASE_URL is set it takes precedence over the DB_* settings, for both the pool and Flyway. */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class DatabaseUrlConfig {

    @Bean
    @ConditionalOnExpression("'${mytripadvision.database.url:}' != ''")
    JdbcConnectionDetails databaseUrlConnectionDetails(@Value("${mytripadvision.database.url}") String databaseUrl,
                                                       @Value("${spring.datasource.username:}") String fallbackUser,
                                                       @Value("${spring.datasource.password:}") String fallbackPassword) {
        DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);
        log.info("Using DATABASE_URL for the database connection");
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return parsed.jdbcUrl();
            }

            @Override
            public String getUsername() {
                return parsed.username() != null ? parsed.username() : fallbackUser;
            }

            @Override
            public String getPassword() {
                return parsed.password() != null ? parsed.password() : fallbackPassword;
            }
        };
    }
}
