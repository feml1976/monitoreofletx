package com.fml.monitoreofletx.integration;

import com.fml.monitoreofletx.sync.application.port.in.SincronizarSolicitudesUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integración de extremo a extremo del ciclo de sincronización:
 * origen real (fixtures de test_origen_mf) -> SincronizarSolicitudesService ->
 * destino real (test_monitoreo_fletx), pasando por origen/upsert/marcarObsoletos
 * tal como lo ejecuta el scheduler en producción.
 * Requiere: docker compose -f docker-compose.test.yml up -d
 *
 * Reutiliza las mismas fixtures dinámicas que OrigenAdapterTest (requests
 * 2001-2004, con created_at relativo a "ahora" para caer dentro de la ventana).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SincronizacionEndToEndTest {

    @Autowired
    @Qualifier("originDataSource")
    private DataSource originDataSource;

    @Autowired
    private SincronizarSolicitudesUseCase sincronizarUseCase;

    @Autowired
    @Qualifier("datamartJdbcTemplate")
    private NamedParameterJdbcTemplate datamartJdbc;

    @BeforeAll
    void seedFixturesDinamicos() throws Exception {
        try (Connection conn = originDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("fixtures/origen-requests-dinamicos.sql"));
        }
    }

    @Test
    void sincronizarPueblaLaVistaVigenteConTodasLasSolicitudesDeLaVentana() {
        sincronizarUseCase.sincronizar();

        assertThat(solicitudesEnVistaVigente())
                .as("las 4 solicitudes de fixtures deben quedar vigentes tras el ciclo completo")
                .containsExactlyInAnyOrder(2001L, 2002L, 2003L, 2004L);
    }

    @Test
    void sincronizarMapeaCorrectamenteUnCampoDeNegocioEnElDestino() {
        sincronizarUseCase.sincronizar();

        String placa = datamartJdbc.queryForObject(
                "SELECT placa_vehiculo FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request = :id",
                Map.of("id", 2001L), String.class);

        assertThat(placa).isEqualTo("ABC123");
    }

    @Test
    void reSincronizarEsIdempotente_noDuplicaFilas() {
        sincronizarUseCase.sincronizar();
        int antes = contarSolicitudes(List.of(2001L, 2002L, 2003L, 2004L));

        sincronizarUseCase.sincronizar();
        int despues = contarSolicitudes(List.of(2001L, 2002L, 2003L, 2004L));

        assertThat(despues)
                .as("un segundo ciclo sobre el mismo origen no debe duplicar filas (upsert por clave natural)")
                .isEqualTo(antes);
    }

    // ------------------------------------------------------------------

    private List<Long> solicitudesEnVistaVigente() {
        return datamartJdbc.query(
                "SELECT solicitud_request FROM test_monitoreo_fletx.v_monitoreo_fletx_vigente WHERE solicitud_request IN (:ids)",
                Map.of("ids", List.of(2001L, 2002L, 2003L, 2004L)),
                (rs, n) -> rs.getLong("solicitud_request"));
    }

    private int contarSolicitudes(List<Long> ids) {
        Integer count = datamartJdbc.queryForObject(
                "SELECT COUNT(*) FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request IN (:ids)",
                Map.of("ids", ids), Integer.class);
        return count == null ? 0 : count;
    }
}
