package com.fml.monitoreofletx.sync.infrastructure.adapter.out.origen;

import com.fml.monitoreofletx.sync.application.port.out.OrigenPort;
import com.fml.monitoreofletx.sync.domain.SolicitudFletx;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Adaptador JDBC de origen (Fletx, SOLO LECTURA). Ejecuta
 * {@code sql/consulta-base.sql} parametrizada por ventana móvil sobre
 * {@code originJdbcTemplate}.
 *
 * eventos_detalle se mapea como String crudo: el driver JDBC entrega el JSON
 * como texto para columnas json/jsonb, sin deserializar a POJO — eso se
 * decide en Etapa C si Analítica lo requiere.
 */
@Component
public class JdbcOrigenAdapter implements OrigenPort {

    private final NamedParameterJdbcTemplate originJdbcTemplate;
    private final String sql;

    public JdbcOrigenAdapter(
            @Qualifier("originJdbcTemplate") NamedParameterJdbcTemplate originJdbcTemplate) {
        this.originJdbcTemplate = originJdbcTemplate;
        try {
            this.sql = StreamUtils.copyToString(
                    new ClassPathResource("sql/consulta-base.sql").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se puede cargar la consulta de origen: sql/consulta-base.sql", e);
        }
    }

    @Override
    public List<SolicitudFletx> consultar(LocalDateTime ventanaInicio, LocalDateTime ventanaFin) {
        var params = new MapSqlParameterSource()
                .addValue("fecha_inicio", ventanaInicio)
                .addValue("fecha_fin",    ventanaFin);

        return originJdbcTemplate.query(sql, params, this::mapRow);
    }

    private SolicitudFletx mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SolicitudFletx(
                // ─── Identificadores ───
                getLong(rs, "reserva_booking"),
                rs.getLong("solicitud_request"),          // CLAVE NATURAL, NOT NULL
                rs.getString("estado_solicitud"),
                rs.getString("descripcion_estado"),
                getLong(rs, "remesa"),
                getLong(rs, "manifiesto"),

                // ─── Vehículo ───
                rs.getString("placa_vehiculo"),
                rs.getString("marca_vehiculo"),
                rs.getString("modelo"),
                rs.getString("color_vehiculo"),
                rs.getString("tipo_carroceria"),
                rs.getString("afiliacion_vehiculo"),
                rs.getString("configuracion_vehiculo"),
                rs.getString("tipo_vehiculo"),
                getInt(rs, "capacidad_vehiculo"),
                getInt(rs, "capacidad_maxima"),

                // ─── Trailer ───
                rs.getString("placa_trailer"),
                rs.getString("tipo_trailer"),
                rs.getString("marca_trailer"),
                rs.getString("color_trailer"),
                rs.getString("peso_vacio"),               // ⚠️ varchar, no numérico
                getInt(rs, "modelo_trailer"),
                getInt(rs, "numero_ejes"),

                // ─── Conductor ─── [P3]
                rs.getString("tipo_documento_conductor"),
                getLong(rs, "documento_conductor"),        // ⚠️ bigint, no String
                rs.getString("nombre_conductor"),
                rs.getString("apellido_conductor"),
                rs.getString("direccion_conductor"),
                rs.getString("ciudad_conductor"),
                rs.getString("dpto_conductor"),
                rs.getString("telefono_conductor"),
                rs.getString("correo_conductor"),
                rs.getString("contacto_emergencia"),
                rs.getString("telefono_emergencia"),
                rs.getString("direccion_emergencia"),
                getDate(rs, "fecha_expedicion"),
                getDate(rs, "fecha_vigencia"),
                getLong(rs, "movil_referencia1"),          // ⚠️ bigint, no String
                getLong(rs, "movil_referencia2"),          // ⚠️ bigint, no String
                getLong(rs, "movil_referencia3"),          // ⚠️ bigint, no String

                // ─── Propietarios y tenedores ─── [P3]
                getLong(rs, "documento_propietario_vehiculo"),
                getInt(rs, "digito_propietario_vehiculo"),
                rs.getString("nombre_propietario_vehiculo"),
                rs.getString("apellido_propietario_vehiculo"),
                getLong(rs, "documento_tenedor_vehiculo"),
                rs.getString("tenedor_vehiculo"),
                getLong(rs, "documento_propietario_trailer"),
                getInt(rs, "digito_propietario_trailer"),
                rs.getString("nombre_propietario_trailer"),
                rs.getString("apellido_propietario_trailer"),
                getLong(rs, "documento_tenedor_trailer"),
                rs.getString("tenedor_trailer"),

                // ─── Negocio y cliente ───
                rs.getString("cliente"),
                rs.getString("tipo_negocio"),
                rs.getString("descripcion_negocio"),
                rs.getString("generador_carga"),
                rs.getString("tipo_identificacion"),
                getLong(rs, "documento"),                  // ⚠️ bigint, no String
                getInt(rs, "digito_verificacion"),
                rs.getString("direccion_generador"),
                rs.getString("telefono_generador"),
                rs.getString("movil_generador"),
                rs.getString("ciudad_generador"),
                rs.getString("departamento_generador"),
                rs.getString("cliente_paga"),
                rs.getString("quien_recibe"),
                rs.getString("telefono_recibe"),
                getBoolean(rs, "otro_recibe"),             // flag, no el receptor

                // ─── Booking / solicitud ───
                rs.getString("tipo_booking"),
                rs.getString("tipo_sider"),
                rs.getString("tipo_distribucion"),
                getDateTime(rs, "fecha_booking"),
                getDateTime(rs, "fecha_reserva"),
                rs.getBigDecimal("flete_conductor"),
                rs.getBigDecimal("flete_empresa"),
                getDateTime(rs, "fecha_carga_solicitud"),
                getLong(rs, "flete"),                      // ⚠️ bigint, no numeric
                getDateTime(rs, "fecha_solicitud"),
                getDateTime(rs, "actualizacion_solicitud"),
                rs.getBigDecimal("valor_pago"),
                getInt(rs, "peso"),
                getInt(rs, "unidades"),
                rs.getString("rndc_remesa"),
                rs.getString("rndc_manifiesto"),
                rs.getString("seguridad_qr"),
                getBoolean(rs, "siniestrado"),
                getInt(rs, "standby"),                     // ⚠️ integer, no boolean
                getBoolean(rs, "flete_exclusivo"),
                getLong(rs, "segundos_destino"),
                getBoolean(rs, "tiene_auction"),
                getLong(rs, "load_id"),
                getLong(rs, "trip_id"),
                rs.getString("tipo_operacion"),
                rs.getString("empresa_despacha"),

                // ─── Ruta ───
                rs.getString("origen"),
                rs.getString("planta_origen"),
                rs.getString("destino"),
                rs.getString("planta_destino"),
                rs.getString("ruta"),
                rs.getString("productos"),

                // ─── Eventos y novedades ───
                getDateTime(rs, "fecha_fin_cargue"),
                getBoolean(rs, "reporto_fin_cargue"),
                rs.getString("ultimo_estado_novedad"),
                rs.getString("novedad"),
                getDateTime(rs, "fecha_novedad"),
                rs.getString("eventos_detalle"),           // JSON crudo (jsonb -> texto vía driver)

                // ─── Cumplidos ─── [A1]
                getLong(rs, "cumplidos_total"),
                getLong(rs, "cumplidos_ok"),
                getLong(rs, "cumplidos_anulados"),
                getDateTime(rs, "fecha_ultimo_cumplido"),
                getBoolean(rs, "viaje_cumplido"),

                // ─── Liquidación y anticipos ─── [A2]
                getLong(rs, "liquidacion_consecutivo"),
                getLong(rs, "anticipos_girados"),
                getLong(rs, "saldo_liquidacion"),
                getLong(rs, "deducciones"),
                getLong(rs, "valor_acordado_conductor"),
                getBoolean(rs, "liquidacion_pagada"),
                getBoolean(rs, "liquidacion_legalizada"),
                getBoolean(rs, "liquidacion_anulada")
        );
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Long.class);
    }

    private Integer getInt(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    private Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Boolean.class);
    }

    private LocalDate getDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private LocalDateTime getDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }
}
