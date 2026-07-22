-- =============================================================================
-- V2 — Tabla de destino completa: radiografía de solicitudes (requests) de Fletx.
-- Schema: ${schema} (mismo que V1/shedlock).
--
-- Tipos tomados EXACTAMENTE de docs/verificacion-tipos-destino-v4.md (gate de
-- viabilidad ejecutado por Claude Desktop contra Fletx real, 2026-07-22) —
-- no se reinterpretan ni se "mejoran" los tipos ahí documentados. Atención
-- especial a las columnas marcadas abajo con hallazgos ⚠️ (peso_vacio es
-- varchar no numérico; standby es integer no boolean; flete es bigint pero
-- valor_pago/flete_conductor/flete_empresa son numeric; los documentos y
-- teléfonos de referencia son bigint no varchar).
--
-- Surrogate BIGINT IDENTITY: tabla de reporte/proyección, no agregado de
-- dominio (mismo patrón que torre_control_viajes en Controlt). La identidad
-- real de negocio es solicitud_request (clave natural, columna única).
-- =============================================================================
CREATE TABLE IF NOT EXISTS ${schema}.solicitudes (
    id                              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- ─── Identificadores ───
    reserva_booking                 bigint,
    solicitud_request               bigint        NOT NULL,   -- CLAVE NATURAL (rq.id)
    estado_solicitud                text,
    descripcion_estado              text,
    remesa                          bigint,
    manifiesto                      bigint,

    -- ─── Vehículo ───
    placa_vehiculo                  varchar(10),
    marca_vehiculo                  text,
    modelo                          text,
    color_vehiculo                  text,
    tipo_carroceria                 text,
    afiliacion_vehiculo             varchar(15),
    configuracion_vehiculo          text,
    tipo_vehiculo                   text,
    capacidad_vehiculo              integer,
    capacidad_maxima                integer,

    -- ─── Trailer ───
    placa_trailer                   varchar(10),
    tipo_trailer                    text,
    marca_trailer                   text,
    color_trailer                   text,
    peso_vacio                      text,          -- ⚠️ varchar en origen, no numérico
    modelo_trailer                  integer,
    numero_ejes                     integer,

    -- ─── Conductor ─── [P3: datos personales]
    tipo_documento_conductor        text,
    documento_conductor             bigint,        -- ⚠️ bigint, no varchar
    nombre_conductor                text,
    apellido_conductor              text,
    direccion_conductor             text,
    ciudad_conductor                text,
    dpto_conductor                  text,
    telefono_conductor              text,
    correo_conductor                text,
    contacto_emergencia             text,
    telefono_emergencia             text,
    direccion_emergencia            text,
    fecha_expedicion                date,
    fecha_vigencia                  date,
    movil_referencia1               bigint,        -- ⚠️ bigint, no varchar
    movil_referencia2               bigint,        -- ⚠️ bigint, no varchar
    movil_referencia3               bigint,        -- ⚠️ bigint, no varchar

    -- ─── Propietarios y tenedores ─── [P3]
    documento_propietario_vehiculo  bigint,        -- ⚠️ bigint, no varchar
    digito_propietario_vehiculo     integer,
    nombre_propietario_vehiculo     text,
    apellido_propietario_vehiculo   text,
    documento_tenedor_vehiculo      bigint,        -- ⚠️ bigint, no varchar
    tenedor_vehiculo                text,
    documento_propietario_trailer   bigint,        -- ⚠️ bigint, no varchar
    digito_propietario_trailer      integer,
    nombre_propietario_trailer      text,
    apellido_propietario_trailer    text,
    documento_tenedor_trailer       bigint,        -- ⚠️ bigint, no varchar
    tenedor_trailer                 text,

    -- ─── Negocio y cliente ───
    cliente                         text,
    tipo_negocio                    text,
    descripcion_negocio             text,
    generador_carga                 text,
    tipo_identificacion             text,
    documento                       bigint,        -- ⚠️ bigint, no varchar (loadgenerators.document)
    digito_verificacion             integer,
    direccion_generador             text,
    telefono_generador              text,
    movil_generador                 text,
    ciudad_generador                text,
    departamento_generador          text,
    cliente_paga                    text,          -- [P1] FK bookings.paga_id tentativo
    quien_recibe                    text,
    telefono_recibe                 text,
    otro_recibe                     boolean,       -- flag "¿alguien más recibe?", no el receptor

    -- ─── Booking / solicitud ───
    tipo_booking                    text,
    tipo_sider                      text,
    tipo_distribucion               text,
    fecha_booking                   timestamp,
    fecha_reserva                   timestamp,
    flete_conductor                 numeric,
    flete_empresa                   numeric,
    fecha_carga_solicitud           timestamp,
    flete                           bigint,        -- ⚠️ bigint (no numeric, a diferencia de valor_pago)
    fecha_solicitud                 timestamp,
    actualizacion_solicitud         timestamp,
    valor_pago                      numeric,
    peso                            integer,
    unidades                        integer,
    rndc_remesa                     text,
    rndc_manifiesto                 text,
    seguridad_qr                    text,
    siniestrado                     boolean,
    standby                         integer,       -- ⚠️ integer, no boolean
    flete_exclusivo                 boolean,
    segundos_destino                bigint,
    tiene_auction                   boolean,
    load_id                         bigint,
    trip_id                         bigint,
    tipo_operacion                  text,
    empresa_despacha                text,

    -- ─── Ruta ───
    origen                          text,
    planta_origen                   text,
    destino                         text,
    planta_destino                  text,
    ruta                            text,
    productos                       text,

    -- ─── Eventos y novedades ───
    fecha_fin_cargue                timestamp,
    reporto_fin_cargue              boolean,
    ultimo_estado_novedad           text,
    novedad                         text,
    fecha_novedad                   timestamp,
    eventos_detalle                 jsonb,         -- ⚠️ json_agg en Fletx devuelve json, cast a jsonb

    -- ─── Cumplidos ─── [A1]
    cumplidos_total                 bigint,
    cumplidos_ok                    bigint,
    cumplidos_anulados              bigint,
    fecha_ultimo_cumplido           timestamp,
    viaje_cumplido                  boolean,

    -- ─── Liquidación y anticipos ─── [A2]
    liquidacion_consecutivo         bigint,
    anticipos_girados                bigint,
    saldo_liquidacion               bigint,
    deducciones                     bigint,
    valor_acordado_conductor        bigint,
    liquidacion_pagada              boolean,
    liquidacion_legalizada          boolean,
    liquidacion_anulada             boolean,

    -- ─── Housekeeping (no vienen de Fletx) ───
    row_hash                        varchar(32)   NOT NULL,
    deleted_at                      timestamp,
    sincronizado_at                 timestamp     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  ${schema}.solicitudes IS
'Read model (proyección CQRS) de la radiografía de solicitudes Fletx. Poblada por job Spring Boot cada ~15 min via upsert. No es fuente de verdad: se reconstruye desde Fletx.';
COMMENT ON COLUMN ${schema}.solicitudes.row_hash IS
'MD5 en Java de las columnas de negocio (nunca md5() de SQL — formato de serialización incompatible); permite detectar cambios y evitar writes innecesarios.';
COMMENT ON COLUMN ${schema}.solicitudes.sincronizado_at IS
'Última vez que el registro fue insertado o actualizado por el job. No confundir con fecha_solicitud/actualizacion_solicitud, que son de Fletx.';
COMMENT ON COLUMN ${schema}.solicitudes.deleted_at IS
'Marca de obsoleto: la solicitud ya no aparece en la ventana de sincronización.';
COMMENT ON COLUMN ${schema}.solicitudes.eventos_detalle IS
'Histórico completo de eventos del viaje (evento_id, fecha_hora, estado_anterior, estado_actual, comentario), incluido el evento génesis sin estado_anterior. jsonb para permitir indexar (GIN) a futuro si Analítica lo pide.';
COMMENT ON COLUMN ${schema}.solicitudes.peso_vacio IS
'trailers.empty_weight es varchar en Fletx, no numérico (a diferencia de vehicles.empty_weight). No castear sin validar.';
COMMENT ON COLUMN ${schema}.solicitudes.standby IS
'requests.standby es integer (contador/código), no boolean. No confundir con businesses.standby.';

-- -----------------------------------------------------------------------------
-- Clave natural única = objetivo del ON CONFLICT (Etapa C).
-- solicitud_request es NOT NULL en origen: a diferencia de Controlt, no se
-- necesita COALESCE de sentinel para nulos.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_solicitudes_natural
    ON ${schema}.solicitudes (solicitud_request);

-- Índices de consulta (criterio de Controlt para su tabla equivalente)
CREATE INDEX IF NOT EXISTS ix_solicitudes_fecha_solicitud
    ON ${schema}.solicitudes (fecha_solicitud DESC);

CREATE INDEX IF NOT EXISTS ix_solicitudes_estado_solicitud
    ON ${schema}.solicitudes (estado_solicitud);

CREATE INDEX IF NOT EXISTS ix_solicitudes_deleted_at
    ON ${schema}.solicitudes (deleted_at);

CREATE INDEX IF NOT EXISTS ix_solicitudes_placa_vehiculo
    ON ${schema}.solicitudes (placa_vehiculo);

CREATE INDEX IF NOT EXISTS ix_solicitudes_cliente
    ON ${schema}.solicitudes (cliente);

-- -----------------------------------------------------------------------------
-- Vista de conveniencia: solicitudes vigentes (no obsoletas).
-- Sin lógica de negocio adicional (a diferencia de Controlt, esta radiografía
-- expone todos los estados, no solo los "activos").
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW ${schema}.v_monitoreo_fletx_vigente AS
SELECT *
FROM ${schema}.solicitudes
WHERE deleted_at IS NULL;

COMMENT ON VIEW ${schema}.v_monitoreo_fletx_vigente IS
'Conveniencia de lectura: solicitudes vigentes (no obsoletas). Sin lógica de negocio.';
