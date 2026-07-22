package com.fml.monitoreofletx.integration;

import com.fml.monitoreofletx.sync.application.port.out.ProyeccionPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.fml.monitoreofletx.sync.domain.SolicitudFletxMother.base;
import static com.fml.monitoreofletx.sync.domain.SolicitudFletxMother.con;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integración del adaptador de proyección.
 * Requiere: docker compose -f docker-compose.test.yml up -d
 *
 * Verifica: INSERT, idempotencia (sin cambios -> 0 filas), UPDATE con hash distinto,
 * clasificación correcta de insertados/actualizados/omitidos, resurrección de
 * soft-deletes (marcarObsoletos se prueba en el Paso 3).
 */
@SpringBootTest
class ProyeccionAdapterTest {

    @Autowired
    private ProyeccionPort proyeccionPort;

    @Autowired
    @Qualifier("datamartJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void insertaUnaSolicitud() {
        var solicitud = base(9001L);

        var stats = proyeccionPort.upsert(List.of(solicitud));

        assertThat(stats.escritas()).isEqualTo(1);
        assertThat(stats.insertados()).isEqualTo(1);
        assertThat(contarPorSolicitud(9001L)).isEqualTo(1);
    }

    @Test
    void upsertIdempotente_sinCambios_noReescribeFila() {
        var solicitud = base(9002L);
        proyeccionPort.upsert(List.of(solicitud));

        // Segunda llamada con la misma solicitud: row_hash identico -> 0 filas afectadas
        var stats = proyeccionPort.upsert(List.of(solicitud));

        assertThat(stats.omitidos()).isEqualTo(1);
        assertThat(stats.escritas()).isZero();
    }

    @Test
    void upsertActualiza_cuandoHashCambia() {
        var original   = base(9003L);
        var modificado = con(original, "estadoSolicitud", "Terminado");
        proyeccionPort.upsert(List.of(original));

        var stats = proyeccionPort.upsert(List.of(modificado));

        assertThat(stats.actualizados()).isEqualTo(1);
        assertThat(estadoPorSolicitud(9003L)).isEqualTo("Terminado");
    }

    @Test
    void listaVaciaRetornaStatsEnCero() {
        var stats = proyeccionPort.upsert(Collections.emptyList());

        assertThat(stats.total()).isZero();
    }

    @Test
    void upsertIdempotente_noMueveSincronizadoAt() {
        var solicitud = base(9020L);
        proyeccionPort.upsert(List.of(solicitud));
        LocalDateTime antes = sincronizadoAtPorSolicitud(9020L);

        // Mismo objeto -> row_hash identico -> DO UPDATE WHERE ... no aplica -> sincronizado_at intacto
        proyeccionPort.upsert(List.of(solicitud));

        assertThat(sincronizadoAtPorSolicitud(9020L))
                .as("sincronizado_at no debe cambiar cuando row_hash es identico")
                .isEqualTo(antes);
    }

    @Test
    void upsertConCambioReal_mueveSincronizadoAt() throws InterruptedException {
        var original   = base(9021L);
        var modificado = con(original, "estadoSolicitud", "Terminado"); // unico campo que cambia -> hash distinto

        proyeccionPort.upsert(List.of(original));
        LocalDateTime antes = sincronizadoAtPorSolicitud(9021L);

        Thread.sleep(50); // garantizar avance de reloj entre transacciones
        proyeccionPort.upsert(List.of(modificado));

        assertThat(sincronizadoAtPorSolicitud(9021L))
                .as("sincronizado_at debe avanzar cuando row_hash cambio")
                .isAfter(antes);
    }

    @Test
    void upsertResucitaSolicitudSoftDeleted() {
        var solicitud = base(9022L);
        proyeccionPort.upsert(List.of(solicitud));
        marcarComoObsoletaManualmente(9022L);
        assertThat(deletedAtPorSolicitud(9022L)).isNotNull();

        // La solicitud reaparece en un ciclo posterior con los mismos datos de negocio
        // (row_hash identico) — el upsert debe limpiar deleted_at igual.
        proyeccionPort.upsert(List.of(solicitud));

        assertThat(deletedAtPorSolicitud(9022L))
                .as("una fila soft-deleted que reaparece en la ventana debe resucitar")
                .isNull();
    }

    // ------------------------------------------------------------------

    private void marcarComoObsoletaManualmente(long solicitudRequest) {
        jdbc.update(
                "UPDATE test_monitoreo_fletx.solicitudes SET deleted_at = now() WHERE solicitud_request = :id",
                Map.of("id", solicitudRequest));
    }

    private int contarPorSolicitud(long solicitudRequest) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request = :id",
                Map.of("id", solicitudRequest), Integer.class);
        return count == null ? 0 : count;
    }

    private String estadoPorSolicitud(long solicitudRequest) {
        return jdbc.queryForObject(
                "SELECT estado_solicitud FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request = :id",
                Map.of("id", solicitudRequest), String.class);
    }

    private LocalDateTime sincronizadoAtPorSolicitud(long solicitudRequest) {
        return jdbc.queryForObject(
                "SELECT sincronizado_at FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request = :id",
                Map.of("id", solicitudRequest), LocalDateTime.class);
    }

    private LocalDateTime deletedAtPorSolicitud(long solicitudRequest) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM test_monitoreo_fletx.solicitudes WHERE solicitud_request = :id",
                Map.of("id", solicitudRequest), LocalDateTime.class);
    }
}
