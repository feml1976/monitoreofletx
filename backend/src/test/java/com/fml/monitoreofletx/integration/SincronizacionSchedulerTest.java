package com.fml.monitoreofletx.integration;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el comportamiento de ShedLock para el scheduler de sincronización.
 * Requiere: docker compose -f docker-compose.test.yml up -d
 *
 * Prueba contra LockProvider directamente (más fiable que pasar por el proxy AOP):
 *  - La tabla shedlock existe (V1 Flyway)
 *  - El provider impide que dos instancias simultáneas ejecuten el job
 */
@SpringBootTest
class SincronizacionSchedulerTest {

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    @Qualifier("datamartJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void tablaSheldlockExisteEnBd_creadaPorMigracionV1() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'test_monitoreo_fletx'
                  AND table_name   = 'shedlock'
                """, Map.of(), Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void lockProviderImpideAdquisicionConcurrente() {
        var config = new LockConfiguration(
                Instant.now(),
                "test-monitoreofletx-concurrencia",
                Duration.ofMinutes(1),
                Duration.ZERO);

        Optional<SimpleLock> primerLock  = lockProvider.lock(config);
        Optional<SimpleLock> segundoLock = lockProvider.lock(config);

        assertThat(primerLock)
                .as("Primera instancia debe adquirir el lock")
                .isPresent();
        assertThat(segundoLock)
                .as("Segunda instancia NO puede adquirir el lock mientras esta activo")
                .isEmpty();

        // Liberar para no dejar estado sucio
        primerLock.ifPresent(SimpleLock::unlock);
    }
}
