package com.mytripadvision.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void renderStyleUrl_becomesJdbc_withCredentials_andSslRequired() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://mytripadvision:p%40ss@db.example.com/tripdb");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com/tripdb?sslmode=require");
        assertThat(url.username()).isEqualTo("mytripadvision");
        assertThat(url.password()).isEqualTo("p@ss");
    }

    @Test
    void existingQuery_isKept_andSslIsAppendedWithAmpersand() {
        assertThat(DatabaseUrl.parse("postgres://u:p@host:5433/db?application_name=x").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host:5433/db?application_name=x&sslmode=require");
    }

    @Test
    void explicitSslMode_isNotOverridden() {
        assertThat(DatabaseUrl.parse("postgresql://u:p@host/db?sslmode=disable").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db?sslmode=disable");
    }

    @Test
    void localDatabase_getsNoSslRequirement() {
        assertThat(DatabaseUrl.parse("postgresql://mytripadvision:mytripadvision@localhost:15432/mytripadvision").jdbcUrl())
                .isEqualTo("jdbc:postgresql://localhost:15432/mytripadvision");
    }

    @Test
    void jdbcUrl_isUsedUnchanged() {
        DatabaseUrl url = DatabaseUrl.parse("jdbc:postgresql://localhost:15432/mytripadvision");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:15432/mytripadvision");
        assertThat(url.username()).isNull();
    }

    @Test
    void garbage_isRejected() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://u:p@host/db")).isInstanceOf(IllegalArgumentException.class);
    }
}
