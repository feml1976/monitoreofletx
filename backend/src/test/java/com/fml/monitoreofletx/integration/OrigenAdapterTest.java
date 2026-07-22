package com.fml.monitoreofletx.integration;

import com.fml.monitoreofletx.sync.application.port.out.OrigenPort;
import com.fml.monitoreofletx.sync.domain.SolicitudFletx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integración del adaptador de origen.
 * Requiere: docker compose -f docker-compose.test.yml up -d
 *
 * Fixtures estáticas (catálogos, booking, personas, vehículo/trailer — sin
 * columnas de fecha filtradas por ventana) sembradas en docker/init-test/03-seed.sql
 * (initdb). Fixtures dinámicas (requests/events/consecutive_ministries/
 * comply_destinations/liquidations, con created_at relativo a "ahora")
 * sembradas en @BeforeAll desde fixtures/origen-requests-dinamicos.sql — un
 * contenedor de test longevo no las deja envejecer fuera de la ventana de
 * sync, porque se insertan en tiempo de ejecución, no al crear el contenedor.
 * DELETE + INSERT idempotente: correr la suite varias veces sin reiniciar el
 * contenedor no duplica ni falla.
 *
 *   - Request 2001: "happy path" — booking 1:1, vehículo+trailer+conductor+
 *     propietarios/tenedores, cumplidos, liquidación, 3 eventos (incluye el
 *     evento génesis sin estado_anterior, ver [B10]).
 *   - Request 2002: sin booking/vehículo/trailer/conductor — todos los LEFT
 *     JOIN deben resolver a NULL sin romper el mapeo.
 *   - Request 2003: booking multi-recogida (2 direcciones de carga).
 *   - Request 2004: cardinalidad 1:N en consecutive_ministries (anomalía real
 *     de Fletx prod, misma clase de bug que ya mordió a Controlt).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrigenAdapterTest {

    @Autowired
    @Qualifier("originDataSource")
    private DataSource originDataSource;

    @BeforeAll
    void seedFixturesDinamicos() throws Exception {
        try (Connection conn = originDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("fixtures/origen-requests-dinamicos.sql"));
        }
    }

    @Autowired
    private OrigenPort origenPort;

    @Test
    void retornaTodasLasSolicitudesEnLaVentana() {
        var resultados = consultar();

        assertThat(resultados)
                .as("La radiografia incluye TODOS los requests en ventana, sin filtrar por estado (a diferencia de Controlt)")
                .extracting(SolicitudFletx::solicitudRequest)
                .containsExactlyInAnyOrder(2001L, 2002L, 2003L, 2004L);
    }

    @Test
    void sinDuplicadosDeSolicitudRequest() {
        var ids = consultar().stream().map(SolicitudFletx::solicitudRequest).toList();

        assertThat(ids).as("No deben existir duplicados de solicitud_request").doesNotHaveDuplicates();
    }

    @Test
    void mapeaHallazgosDeTipos_documentosYMovilesComoLong() {
        var s = porSolicitud(2001L);

        assertThat(s.documentoConductor())
                .as("documento_conductor es bigint en origen, no debe mapearse como String")
                .isEqualTo(1020304050L);
        assertThat(s.movilReferencia1()).isEqualTo(3001234567L);
        assertThat(s.movilReferencia2()).isEqualTo(3007654321L);
        assertThat(s.movilReferencia3()).isEqualTo(3009876543L);
        assertThat(s.documentoPropietarioVehiculo()).isEqualTo(500111222L);
        assertThat(s.documentoTenedorVehiculo()).isEqualTo(500333444L);
        assertThat(s.documentoPropietarioTrailer()).isEqualTo(500555666L);
        assertThat(s.documentoTenedorTrailer()).isEqualTo(500777888L);
        assertThat(s.documento())
                .as("loadgenerators.document es bigint, no varchar")
                .isEqualTo(900123456L);
    }

    @Test
    void mapeaHallazgo_pesoVacioComoTextoNoNumerico() {
        var s = porSolicitud(2001L);

        assertThat(s.pesoVacio())
                .as("trailers.empty_weight es varchar en Fletx: no debe intentarse castear a numero")
                .isEqualTo("2.5 Ton");
    }

    @Test
    void mapeaHallazgo_standbyComoIntegerNoBoolean() {
        var s = porSolicitud(2001L);

        assertThat(s.standby())
                .as("requests.standby es integer (contador/codigo), no boolean")
                .isEqualTo(2);
    }

    @Test
    void mapeaHallazgo_fleteComoLongDistintoDeValorPagoNumeric() {
        var s = porSolicitud(2001L);

        assertThat(s.flete())
                .as("requests.freight es bigint")
                .isEqualTo(5_000_000L);
        assertThat(s.valorPago())
                .as("requests.value_pay_to_driver es numeric — precision de BigDecimal preservada")
                .isEqualTo(new BigDecimal("1234567.89"));
        assertThat(s.fleteConductor()).isEqualTo(new BigDecimal("150000.50"));
        assertThat(s.fleteEmpresa()).isEqualTo(new BigDecimal("200000.75"));
    }

    @Test
    void mapeaHallazgo_otroRecibeEsFlagDistintoDeQuienRecibe() {
        var s = porSolicitud(2001L);

        assertThat(s.otroRecibe())
                .as("otro_recibe es boolean: ¿alguien mas recibe?, no el nombre del receptor")
                .isTrue();
        assertThat(s.quienRecibe())
                .as("quien_recibe es el nombre del receptor, campo distinto de otro_recibe")
                .isEqualTo("Ana Receptora");
        assertThat(s.telefonoRecibe()).isEqualTo("3011112222");
    }

    @Test
    void mapeaEventosDetalleComoJsonConEventoGenesis() {
        var s = porSolicitud(2001L);

        // jsonb reformatea el texto (espacio tras ":" y ",") y NO preserva el orden
        // de claves del json_agg original (json si lo preserva) — ver hallazgo ⚠️
        // eventos_detalle del documento de verificacion. Cada assert comprueba un
        // par clave:valor de forma independiente, sin asumir orden entre objetos.
        assertThat(s.eventosDetalle())
                .as("eventos_detalle debe llegar como JSON crudo (String), incluyendo el evento genesis sin estado_anterior")
                .isNotNull()
                .contains("\"estado_anterior\": null")
                .contains("\"estado_actual\": \"Creado\"")
                .contains("\"comentario\": \"Solicitud creada\"")
                .contains("\"estado_actual\": \"Fin de Cargue\"")
                .contains("\"estado_actual\": \"En transito\"");
    }

    @Test
    void mapeaCamposDeNegocioYRelaciones() {
        var s = porSolicitud(2001L);

        assertThat(s.placaVehiculo()).isEqualTo("ABC123");
        assertThat(s.placaTrailer()).isEqualTo("R99999");
        assertThat(s.origen()).isEqualTo("Bogota");
        assertThat(s.destino()).isEqualTo("Medellin");
        assertThat(s.ruta()).isEqualTo("BOG-MED");
        assertThat(s.nombreConductor()).isEqualTo("Juan Carlos");
        assertThat(s.apellidoConductor()).isEqualTo("Perez Gomez");
        assertThat(s.cliente()).isEqualTo("Cliente Demo S.A.");
        assertThat(s.afiliacionVehiculo()).isEqualTo("Propio");
        assertThat(s.tipoVehiculo()).isEqualTo("Planchon");
        assertThat(s.productos()).isEqualTo("COD-001");
        assertThat(s.remesa()).isEqualTo(20001L);
        assertThat(s.manifiesto()).isEqualTo(10001L);
        // Los 5 alias de "people" no deben mezclarse entre si.
        // "Ana " con espacio final: concat de secondname NULL via COALESCE(...,'') — no es un bug.
        assertThat(s.nombrePropietarioVehiculo()).isEqualTo("Ana ");
        assertThat(s.tenedorVehiculo()).isEqualTo("Hilda Holder");
        assertThat(s.nombrePropietarioTrailer()).isEqualTo("Oscar ");
        assertThat(s.tenedorTrailer()).isEqualTo("Rosa TeneTr");
    }

    @Test
    void mapeaCumplidosYLiquidacion() {
        var s = porSolicitud(2001L);

        assertThat(s.cumplidosTotal()).isEqualTo(2L);
        assertThat(s.cumplidosOk()).isEqualTo(2L);
        assertThat(s.cumplidosAnulados()).isEqualTo(0L);
        assertThat(s.viajeCumplido()).isTrue();

        assertThat(s.liquidacionConsecutivo()).isEqualTo(555L);
        assertThat(s.anticiposGirados()).isEqualTo(100_000L);
        assertThat(s.saldoLiquidacion()).isEqualTo(50_000L);
        assertThat(s.deducciones()).isEqualTo(5_000L);
        assertThat(s.valorAcordadoConductor()).isEqualTo(1_200_000L);
        assertThat(s.liquidacionPagada()).isTrue();
        assertThat(s.liquidacionLegalizada()).isTrue();
        assertThat(s.liquidacionAnulada()).isFalse();
    }

    @Test
    void mapeaFechasComoLocalDateTime() {
        var s = porSolicitud(2001L);

        assertThat(s.fechaSolicitud()).isInstanceOf(LocalDateTime.class).isNotNull();
        assertThat(s.actualizacionSolicitud()).isNotNull();
        assertThat(s.fechaExpedicion()).isNotNull();
    }

    @Test
    void solicitudSinBookingNiVehiculo_camposQuedanNulosSinFallar() {
        var s = porSolicitud(2002L);

        assertThat(s.reservaBooking()).isNull();
        assertThat(s.placaVehiculo()).isNull();
        assertThat(s.placaTrailer()).isNull();
        assertThat(s.documentoConductor()).isNull();
        assertThat(s.plantaOrigen()).isNull();
        assertThat(s.plantaDestino()).isNull();
        assertThat(s.cliente()).isNull();
    }

    @Test
    void mapeaPlantaOrigenYDestino_bookingUnoAUno() {
        var s = porSolicitud(2001L);

        assertThat(s.plantaOrigen()).isEqualTo("Planta Fusagasuga");
        assertThat(s.plantaDestino()).isEqualTo("Bodega Cali");
    }

    @Test
    void mapeaPlantaOrigen_bookingMultiRecogida_concatenaDireccionesDeCarga() {
        var s = porSolicitud(2003L);

        assertThat(s.plantaOrigen())
                .as("2 direcciones de carga deben concatenarse en orden alfabetico")
                .isEqualTo("Planta Facatativa, Planta Girardot");
        assertThat(s.plantaDestino()).isEqualTo("Bodega Unica");
    }

    @Test
    void deduplicaConsecutiveMinistries_devuelveFilaConDatosReales() {
        // Request 2004 tiene DOS filas en consecutive_ministries (anomalia 1:N real
        // de Fletx prod). El CTE 'ministries' con DISTINCT ON debe devolver
        // exactamente una fila con los consecutivos reales, descartando la huerfana NULL.
        var s = porSolicitud(2004L);

        assertThat(s.manifiesto())
                .as("manifiesto debe ser la fila real (30001), no la huerfana NULL")
                .isEqualTo(30001L);
        assertThat(s.remesa())
                .as("remesa debe ser la fila real (40001), no la huerfana NULL")
                .isEqualTo(40001L);
    }

    @Test
    void windowExcludeRequestsFueraDeRango() {
        var ventanaFutura = LocalDateTime.now().plusDays(10);
        var resultados = origenPort.consultar(ventanaFutura, ventanaFutura.plusDays(1));

        assertThat(resultados).isEmpty();
    }

    // ------------------------------------------------------------------

    private List<SolicitudFletx> consultar() {
        return origenPort.consultar(
                LocalDateTime.now().minusDays(7),
                LocalDateTime.now().plusDays(1));
    }

    private SolicitudFletx porSolicitud(long solicitudRequest) {
        return consultar().stream()
                .filter(s -> s.solicitudRequest() == solicitudRequest)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro solicitud_request=" + solicitudRequest));
    }
}
