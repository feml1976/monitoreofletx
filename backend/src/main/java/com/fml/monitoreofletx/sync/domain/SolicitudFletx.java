package com.fml.monitoreofletx.sync.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Radiografía de una solicitud (request) de Fletx: los 119 campos de negocio de
 * {@code monitoreofletx-consulta-base-v4.sql}, con los tipos verificados columna
 * por columna contra Fletx real en {@code docs/verificacion-tipos-destino-v4.md}
 * (gate ejecutado por Claude Desktop, 2026-07-22).
 *
 * Los campos de control (row_hash, deleted_at, sincronizado_at) son
 * responsabilidad del adaptador de proyección (Etapa C), no del dominio.
 *
 * ⚠️ Hallazgos que rompen el tipo "obvio" — ver el documento de verificación
 * antes de tocar estos campos:
 * <ul>
 *   <li>{@code pesoVacio} (trailers.empty_weight): {@code String}, NO numérico
 *       (a diferencia de vehicles.empty_weight, que sí es integer).</li>
 *   <li>{@code standby} (requests.standby): {@code Integer}, NO boolean (no
 *       confundir con businesses.standby, que sí es boolean).</li>
 *   <li>{@code flete} (requests.freight): {@code Long}. Las otras tres columnas
 *       de "plata" tienen tipos distintos: valorPago/fleteConductor/fleteEmpresa
 *       son BigDecimal (numeric).</li>
 *   <li>{@code documentoConductor}, {@code documentoPropietario*},
 *       {@code documentoTenedor*}, {@code documento} (generador),
 *       {@code movilReferencia1/2/3}: {@code Long}, NO String — las cédulas/NIT
 *       y teléfonos de referencia viven como número en Fletx.</li>
 *   <li>{@code eventosDetalle}: String crudo de JSON. {@code json_agg} en Fletx
 *       devuelve {@code json}, no {@code jsonb}; se castea explícitamente en el
 *       SELECT de origen. La columna destino sí es {@code jsonb}.</li>
 * </ul>
 *
 * {@code otroRecibe} (requests.another_receives) es un flag — "¿alguien más
 * recibe la carga?" — no el nombre de quien recibe (eso es {@code quienRecibe}).
 * Nombrado de forma confusa en el origen; no confundir ambos campos.
 */
public record SolicitudFletx(

        // ─── Identificadores ───
        Long reservaBooking,
        long solicitudRequest,              // CLAVE NATURAL — rq.id, NOT NULL
        String estadoSolicitud,
        String descripcionEstado,
        Long remesa,
        Long manifiesto,

        // ─── Vehículo ───
        String placaVehiculo,
        String marcaVehiculo,
        String modelo,
        String colorVehiculo,
        String tipoCarroceria,
        String afiliacionVehiculo,
        String configuracionVehiculo,
        String tipoVehiculo,
        Integer capacidadVehiculo,
        Integer capacidadMaxima,

        // ─── Trailer ───
        String placaTrailer,
        String tipoTrailer,
        String marcaTrailer,
        String colorTrailer,
        String pesoVacio,                   // ⚠️ varchar en origen, no numérico
        Integer modeloTrailer,
        Integer numeroEjes,

        // ─── Conductor (datos personales) ───
        String tipoDocumentoConductor,
        Long documentoConductor,            // ⚠️ bigint, no String
        String nombreConductor,
        String apellidoConductor,
        String direccionConductor,
        String ciudadConductor,
        String dptoConductor,
        String telefonoConductor,
        String correoConductor,
        String contactoEmergencia,
        String telefonoEmergencia,
        String direccionEmergencia,
        LocalDate fechaExpedicion,
        LocalDate fechaVigencia,
        Long movilReferencia1,              // ⚠️ bigint, no String
        Long movilReferencia2,              // ⚠️ bigint, no String
        Long movilReferencia3,              // ⚠️ bigint, no String

        // ─── Propietarios y tenedores ───
        Long documentoPropietarioVehiculo,   // ⚠️ bigint, no String
        Integer digitoPropietarioVehiculo,
        String nombrePropietarioVehiculo,
        String apellidoPropietarioVehiculo,
        Long documentoTenedorVehiculo,       // ⚠️ bigint, no String
        String tenedorVehiculo,
        Long documentoPropietarioTrailer,    // ⚠️ bigint, no String
        Integer digitoPropietarioTrailer,
        String nombrePropietarioTrailer,
        String apellidoPropietarioTrailer,
        Long documentoTenedorTrailer,        // ⚠️ bigint, no String
        String tenedorTrailer,

        // ─── Negocio y cliente ───
        String cliente,
        String tipoNegocio,
        String descripcionNegocio,
        String generadorCarga,
        String tipoIdentificacion,
        Long documento,                     // ⚠️ bigint, no String (loadgenerators.document)
        Integer digitoVerificacion,
        String direccionGenerador,
        String telefonoGenerador,
        String movilGenerador,
        String ciudadGenerador,
        String departamentoGenerador,
        String clientePaga,                 // [P1] FK bookings.paga_id tentativo
        String quienRecibe,
        String telefonoRecibe,
        Boolean otroRecibe,                 // flag "¿alguien más recibe?" — ver Javadoc de la clase

        // ─── Booking / solicitud ───
        String tipoBooking,
        String tipoSider,
        String tipoDistribucion,
        LocalDateTime fechaBooking,
        LocalDateTime fechaReserva,
        BigDecimal fleteConductor,
        BigDecimal fleteEmpresa,
        LocalDateTime fechaCargaSolicitud,
        Long flete,                         // ⚠️ bigint (no numeric, a diferencia de valorPago)
        LocalDateTime fechaSolicitud,
        LocalDateTime actualizacionSolicitud,
        BigDecimal valorPago,
        Integer peso,
        Integer unidades,
        String rndcRemesa,
        String rndcManifiesto,
        String seguridadQr,
        Boolean siniestrado,
        Integer standby,                    // ⚠️ integer, no boolean
        Boolean fleteExclusivo,
        Long segundosDestino,
        Boolean tieneAuction,
        Long loadId,
        Long tripId,
        String tipoOperacion,
        String empresaDespacha,

        // ─── Ruta ───
        String origen,
        String plantaOrigen,
        String destino,
        String plantaDestino,
        String ruta,
        String productos,

        // ─── Eventos y novedades ───
        LocalDateTime fechaFinCargue,
        Boolean reportoFinCargue,
        String ultimoEstadoNovedad,
        String novedad,
        LocalDateTime fechaNovedad,
        String eventosDetalle,              // ⚠️ jsonb en destino, String crudo en Java

        // ─── Cumplidos ───
        Long cumplidosTotal,
        Long cumplidosOk,
        Long cumplidosAnulados,
        LocalDateTime fechaUltimoCumplido,
        Boolean viajeCumplido,

        // ─── Liquidación y anticipos ───
        Long liquidacionConsecutivo,
        Long anticiposGirados,
        Long saldoLiquidacion,
        Long deducciones,
        Long valorAcordadoConductor,
        Boolean liquidacionPagada,
        Boolean liquidacionLegalizada,
        Boolean liquidacionAnulada
) {}
