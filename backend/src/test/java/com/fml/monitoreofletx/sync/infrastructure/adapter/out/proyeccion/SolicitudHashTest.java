package com.fml.monitoreofletx.sync.infrastructure.adapter.out.proyeccion;

import com.fml.monitoreofletx.sync.domain.SolicitudFletx;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica las garantías de determinismo y distinguibilidad de computeHash
 * sobre los 119 campos de negocio de SolicitudFletx.
 * Tests unitarios puros: sin Spring context, sin BD.
 */
class SolicitudHashTest {

    @Test
    void mismosDatos_producenMismoHash() {
        var s = base();
        assertThat(JdbcProyeccionAdapter.computeHash(s))
                .isEqualTo(JdbcProyeccionAdapter.computeHash(s));
    }

    @Test
    void hashTieneLongitudMd5() {
        assertThat(JdbcProyeccionAdapter.computeHash(base())).hasSize(32);
    }

    @Test
    void nullVsVacioEnCampoString_producenHashesDistintos() {
        var conNull  = conCampo(base(), "nombreConductor", null);
        var conVacio = conCampo(base(), "nombreConductor", "");

        assertThat(JdbcProyeccionAdapter.computeHash(conNull))
                .as("null y cadena vacia deben dar hashes distintos")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(conVacio));
    }

    @Test
    void nullVsCeroEnCampoLong_producenHashesDistintos() {
        var conNull = conCampo(base(), "remesa", null);
        var conCero = conCampo(base(), "remesa", 0L);

        assertThat(JdbcProyeccionAdapter.computeHash(conNull))
                .as("remesa null y remesa 0 deben dar hashes distintos")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(conCero));
    }

    @Test
    void nullVsFechaEnCampoLocalDateTime_producenHashesDistintos() {
        var conNull  = conCampo(base(), "fechaNovedad", null);
        var conFecha = conCampo(base(), "fechaNovedad", LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(JdbcProyeccionAdapter.computeHash(conNull))
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(conFecha));
    }

    @Test
    void cambioDeCampoString_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "cliente", "Otro Cliente S.A.");

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoLong_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "manifiesto", 99999L);

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoInteger_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "capacidadVehiculo", 99);

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoBoolean_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "siniestrado", true);

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoBigDecimal_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "valorPago", new BigDecimal("1.00"));

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoLocalDate_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "fechaExpedicion", LocalDate.of(2020, 1, 1));

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioDeCampoLocalDateTime_producenHashesDistintos() {
        var a = base();
        var b = conCampo(a, "fechaSolicitud", LocalDateTime.of(2020, 1, 1, 0, 0));

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void cambioEventosDetalle_producenHashesDistintos() {
        // Un evento nuevo en Fletx debe cambiar el hash y disparar el UPDATE.
        var a = base();
        var b = conCampo(a, "eventosDetalle",
                a.eventosDetalle().replace("\"Creado\"", "\"Creado\"},{\"evento_id\":2,\"estado_actual\":\"En transito\""));

        assertThat(JdbcProyeccionAdapter.computeHash(a)).isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    // --- Hallazgos ⚠️ del documento de verificación de tipos ---

    @Test
    void hallazgo_pesoVacioEsTextoYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "pesoVacio", "3.0 Ton");

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("peso_vacio es varchar en origen; su cambio debe afectar el hash igual que cualquier String")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_standbyEsIntegerYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "standby", 5);

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("standby es integer (contador/codigo), no boolean")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_fleteEsLongDistintoDeValorPagoYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "flete", 6_000_000L);

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("flete es bigint, a diferencia de valor_pago/flete_conductor/flete_empresa (numeric)")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_documentoConductorEsLongYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "documentoConductor", 999888777L);

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("documento_conductor es bigint, no String")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_movilReferencia1EsLongYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "movilReferencia1", 3009999999L);

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("movil_referencia1 es bigint, no String")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_eventosDetalleEsJsonCrudoYAfectaElHash() {
        var a = base();
        var b = conCampo(a, "eventosDetalle", "[]");

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("eventos_detalle entra al hash como texto crudo, sin reformatear")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(b));
    }

    @Test
    void hallazgo_otroRecibeEsFlagIndependienteDeQuienRecibe() {
        var a = base();
        var soloOtroRecibeCambia = conCampo(a, "otroRecibe", !a.otroRecibe());
        var soloQuienRecibeCambia = conCampo(a, "quienRecibe", "Otro Receptor");

        assertThat(JdbcProyeccionAdapter.computeHash(a))
                .as("otro_recibe (flag) y quien_recibe (nombre) son campos independientes: ambos deben afectar el hash por separado")
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(soloOtroRecibeCambia))
                .isNotEqualTo(JdbcProyeccionAdapter.computeHash(soloQuienRecibeCambia));
    }

    // ------------------------------------------------------------------

    /** Instancia con los 119 campos poblados, usada como base para las variantes. */
    private static SolicitudFletx base() {
        LocalDate fecha = LocalDate.of(2026, 1, 10);
        LocalDateTime fh = LocalDateTime.of(2026, 1, 15, 8, 30);
        return new SolicitudFletx(
                1000L, 2001L, "Activo", "En proceso", 20001L, 10001L,
                "ABC123", "Kenworth", "T800", "Blanco", "Planchon", "Propio", "6x4", "Tractocamion", 34, 40,
                "R99999", "Sencillo", "Trocal", "Gris", "2.5 Ton", 2015, 3,
                "CC", 1020304050L, "Juan Carlos", "Perez Gomez", "Calle 1", "Bogota", "Cundinamarca", "3001112233", "juan@test.com",
                "Maria Perez", "3002223344", "Calle 2", fecha, fecha.plusYears(5),
                3001234567L, 3007654321L, 3009876543L,
                500111222L, 3, "Ana", "Torres", 500333444L, "Hilda Holder", 500555666L, 7, "Oscar", "Mendez", 500777888L, "Rosa TeneTr",
                "Cliente Demo S.A.", "Transporte", "Carga seca", "Generador X", "NIT", 900123456L, 5, "Calle 3", "3003334455", "3004445566", "Cali", "Valle",
                "ClientePaga S.A.", "Ana Receptora", "3011112222", true,
                "Directo", "Sider1", "Nacional", fh, fh.plusHours(1),
                new BigDecimal("150000.50"), new BigDecimal("200000.75"), fh.plusHours(2),
                5_000_000L, fh, fh.plusHours(3),
                new BigDecimal("1234567.89"), 15, 2,
                "REM-001", "MAN-001", "QR123", false, 2, true, 3600L, false, 7001L, 8001L, "Terrestre", "EmpresaX",
                "Bogota", "Planta Fusagasuga", "Medellin", "Bodega Cali", "BOG-MED", "COD-001",
                fh.plusHours(4), true, "Resuelta", "Retraso menor", fh.plusHours(5),
                "[{\"evento_id\":1,\"estado_anterior\":null,\"estado_actual\":\"Creado\"}]",
                2L, 2L, 0L, fh.plusHours(6), true,
                555L, 100_000L, 50_000L, 5_000L, 1_200_000L, true, true, false
        );
    }

    /**
     * Copia {@code base} reemplazando únicamente el componente {@code nombreComponente}.
     * Evita repetir el constructor de 119 posiciones en cada test: lee los valores
     * actuales del record vía reflection y reconstruye con el valor sustituido.
     */
    private static SolicitudFletx conCampo(SolicitudFletx base, String nombreComponente, Object valor) {
        try {
            var componentes = SolicitudFletx.class.getRecordComponents();
            var tipos = new Class<?>[componentes.length];
            var valores = new Object[componentes.length];
            for (int i = 0; i < componentes.length; i++) {
                var c = componentes[i];
                tipos[i] = c.getType();
                valores[i] = c.getName().equals(nombreComponente) ? valor : c.getAccessor().invoke(base);
            }
            return SolicitudFletx.class.getDeclaredConstructor(tipos).newInstance(valores);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
