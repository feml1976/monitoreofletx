package com.fml.monitoreofletx.sync.infrastructure.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class FlywayConfig {

    /**
     * Flyway gestionado manualmente para apuntar exclusivamente a datamartDataSource.
     * El schema se toma de monitoreofletx.flyway.schema (monitoreo_fletx en prod,
     * test_monitoreo_fletx en tests) y se inyecta como placeholder ${schema} en los
     * scripts de migración.
     *
     * initMethod = "migrate" → Flyway ejecuta las migraciones pendientes al arrancar.
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(
            @Qualifier("datamartDataSource") DataSource datamartDataSource,
            @Value("${monitoreofletx.flyway.schema:monitoreo_fletx}") String schema) {

        return Flyway.configure()
                .dataSource(datamartDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .createSchemas(true)
                .placeholders(Map.of("schema", schema))
                .load();
    }
}
