package com.fml.monitoreofletx.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el contexto Spring levanta y que ambos DataSource conectan
 * contra PostgreSQL de test (Flyway migra V1__crea_shedlock.sql al arrancar).
 * Requiere: docker compose -f docker-compose.test.yml up -d
 */
@SpringBootTest
class DataSourceContextTest {

    @Autowired
    @Qualifier("datamartDataSource")
    private DataSource datamartDataSource;

    @Autowired
    @Qualifier("originDataSource")
    private DataSource originDataSource;

    @Test
    void datamartDataSourceConnectsAndResponds() throws Exception {
        try (Connection connection = datamartDataSource.getConnection()) {
            assertThat(connection.isValid(3)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo("monitoreofletx_test");
        }
    }

    @Test
    void originDataSourceConnectsAndResponds() throws Exception {
        try (Connection connection = originDataSource.getConnection()) {
            assertThat(connection.isValid(3)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo("monitoreofletx_test");
        }
    }
}
