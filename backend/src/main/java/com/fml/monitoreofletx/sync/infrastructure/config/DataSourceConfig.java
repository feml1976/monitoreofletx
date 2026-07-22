package com.fml.monitoreofletx.sync.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    // ------------------------------------------------------------------
    // datamart — destino (lectura/escritura, @Primary)
    // ------------------------------------------------------------------

    @Bean("datamartDataSource")
    @Primary
    @ConfigurationProperties("monitoreofletx.datasource.datamart")
    public HikariDataSource datamartDataSource() {
        return new HikariDataSource();
    }

    @Bean("datamartJdbcTemplate")
    @Primary
    public NamedParameterJdbcTemplate datamartJdbcTemplate(
            @Qualifier("datamartDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // origen (Fletx) — SOLO LECTURA, sin DDL
    // initialization-fail-timeout=-1 en application.yml permite que el
    // pool no bloquee el arranque cuando el origen no es accesible en dev.
    // ------------------------------------------------------------------

    @Bean("originDataSource")
    @ConfigurationProperties("monitoreofletx.datasource.origin")
    public HikariDataSource originDataSource() {
        return new HikariDataSource();
    }

    @Bean("originJdbcTemplate")
    public NamedParameterJdbcTemplate originJdbcTemplate(
            @Qualifier("originDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
