-- =============================================================================
-- MonitoreoFletx — Consulta base v4 (radiografía de solicitudes Fletx), adaptada
-- para el ETL: ventana parametrizada por named parameters de fecha inicio/fin
-- (half-open, fecha inicio incluida y fecha fin excluida) en vez de now() menos
-- un intervalo fijo de 7 dias.
--
-- Sin prefijo de schema: el search_path de la conexión determina el schema.
--   prod  → search_path = public          (default PostgreSQL)
--   test  → search_path = test_origen_mf  (connection-init-sql del pool de test)
--
-- ORIGEN SOLO LECTURA: este archivo no crea ningún objeto en la BD.
-- Fuente: monitoreofletx-consulta-base-v4.sql (raíz del repo) — ver ahí el
-- detalle completo de correcciones [B1-B10] y agregados [A1-A8]. Sin el
-- WHERE rq.id IN (...) de prueba puntual (ya removido en la v4 committeada).
-- =============================================================================
WITH
req_window AS (
    SELECT r.id, r.booking_id
    FROM requests r
    WHERE r.created_at >= :fecha_inicio
      AND r.created_at <  :fecha_fin
),
novedades AS (
    SELECT e.request_id,
           STRING_AGG(rs.name, ' -> ' ORDER BY e.created_at ASC, e.id ASC) AS estados_novedad,
           MAX(e.created_at)                                              AS fecha_ultima_novedad,
           (ARRAY_AGG(rs.name ORDER BY e.created_at DESC, e.id DESC))[1]  AS ultimo_estado_novedad
    FROM events e
    JOIN req_window w ON w.id = e.request_id
    JOIN requeststatuses rs ON rs.id = e.requeststatus_id
    GROUP BY e.request_id
),
-- [A7] histórico completo de eventos como JSON, para Analítica.
-- LEFT JOIN en ambos lados (ver [B10]): el evento génesis no tiene estado
-- anterior y debe seguir apareciendo en el arreglo.
eventos_json AS (
    SELECT e.request_id,
           json_agg(
               json_build_object(
                   'evento_id',        e.id,
                   'fecha_hora',       e.created_at,
                   'estado_anterior',  rqs_old.name,
                   'estado_actual',    rqs_act.name,
                   'comentario',       e.comment
               )
               ORDER BY e.created_at ASC, e.id ASC
           ) AS eventos_detalle
    FROM events e
    JOIN req_window w ON w.id = e.request_id
    LEFT JOIN requeststatuses rqs_old ON rqs_old.id = e.requeststatatusold_id
    LEFT JOIN requeststatuses rqs_act ON rqs_act.id = e.requeststatus_id
    GROUP BY e.request_id
),
fin_cargue AS (
    SELECT e.request_id, MAX(e.created_at) AS fecha_fin_cargue
    FROM events e
    JOIN req_window w ON w.id = e.request_id
    WHERE e.requeststatus_id = 7
    GROUP BY e.request_id
),
productos AS (
    SELECT bp.business_id,
           STRING_AGG(bp.description, ', ' ORDER BY bp.id ASC) AS productos_descripcion,
           STRING_AGG(pc.value,       ', ' ORDER BY bp.id ASC) AS productos_codigo
    FROM businessproducts bp
    LEFT JOIN productcodes pc ON pc.id = bp.productcode_id
    WHERE bp.status = true
      AND bp.business_id IN (SELECT b.business_id FROM bookings b
                             JOIN req_window w ON w.booking_id = b.id
                             WHERE b.business_id IS NOT NULL)
    GROUP BY bp.business_id
),
ministries AS (
    SELECT DISTINCT ON (cm.request_id)
           cm.request_id, cm.consecutive_manifest, cm.consecutive_remesa
    FROM consecutive_ministries cm
    JOIN req_window w ON w.id = cm.request_id
    ORDER BY cm.request_id, (cm.consecutive_manifest IS NULL), cm.id DESC
),
cumplidos AS (
    SELECT cd.request_id,
           COUNT(*)                                            AS cumplidos_total,
           COUNT(*) FILTER (WHERE cd.accomplished)             AS cumplidos_ok,
           COUNT(*) FILTER (WHERE cd.annulled)                 AS cumplidos_anulados,
           MAX(cd.date_comply)                                 AS fecha_ultimo_cumplido,
           BOOL_AND(cd.accomplished) FILTER (WHERE NOT cd.annulled) AS viaje_cumplido
    FROM comply_destinations cd
    JOIN req_window w ON w.id = cd.request_id
    GROUP BY cd.request_id
),
liquidacion AS (
    -- Liquidación vigente por request: prefiere no-anulada y la más reciente
    SELECT DISTINCT ON (l.request_id)
           l.request_id,
           l.consecutive                 AS liquidacion_consecutivo,
           l.advance                     AS anticipos_girados,
           l.balance                     AS saldo_liquidacion,
           l.deductions                  AS deducciones,
           l.agreed_value_to_pay_driver  AS valor_acordado_conductor,
           l.was_paid                    AS liquidacion_pagada,
           l.legalized                   AS liquidacion_legalizada,
           (l.date_annulled IS NOT NULL) AS liquidacion_anulada
    FROM liquidations l
    JOIN req_window w ON w.id = l.request_id
    ORDER BY l.request_id, (l.date_annulled IS NOT NULL), l.id DESC
),
-- [A8] planta origen y planta destino por booking. booking_addresses puede
-- tener varias direcciones de carga (load_address) y/o descarga
-- (unload_address) para un mismo booking (multi-recogida/multi-entrega).
-- Se agrega con STRING_AGG(DISTINCT ...) para devolver una sola fila por
-- booking_id y no duplicar filas del request en el JOIN.
plantas AS (
    -- [B11] FIX (gate EXPLAIN Etapa B, 2026-07-22): faltaba el pushdown de
    -- ventana. Sin el JOIN a req_window, este CTE agregaba booking_addresses
    -- COMPLETA (1,7M filas en Fletx real) en cada ejecución, igual que el bug
    -- [B5] ya corregido para "events". Con el pushdown: cost 333.125 -> 32.197
    -- (Index Scan sobre index_booking_addresses_on_booking_id en vez de Seq
    -- Scan completo). Medido contra Fletx real, misma ventana de 7 dias.
    SELECT
        ba.booking_id,
        STRING_AGG(DISTINCT ad.name, ', ' ORDER BY ad.name)
            FILTER (WHERE ba.load_address = true)                       AS planta_origen,
        STRING_AGG(DISTINCT ad.name, ', ' ORDER BY ad.name)
            FILTER (WHERE ba.unload_address = true)                    AS planta_destino
    FROM booking_addresses ba
    JOIN req_window w ON w.booking_id = ba.booking_id
    INNER JOIN addresses ad ON ad.id = ba.address_id
    GROUP BY ba.booking_id
)
SELECT
    -- ─── Identificadores ───
    bk.id                          AS reserva_booking,
    rq.id                          AS solicitud_request,          -- CLAVE NATURAL
    rt_rq.name                     AS estado_solicitud,
    rt_rq.description              AS descripcion_estado,
    cm.consecutive_remesa          AS remesa,
    cm.consecutive_manifest        AS manifiesto,
    -- ─── Vehículo ───
    vh.placa                       AS placa_vehiculo,
    ck.name                        AS marca_vehiculo,
    cls.value                      AS modelo,
    ccl.name                       AS color_vehiculo,
    cct_vh.name                    AS tipo_carroceria,
    CASE WHEN vh.is_owner THEN 'Propio'
         WHEN vh.is_owner = false AND vh.is_affiliate = 1 THEN 'Afiliado'
         ELSE 'Tercero' END        AS afiliacion_vehiculo,
    cc.description                 AS configuracion_vehiculo,
    ct.name                        AS tipo_vehiculo,
    cc.capacity                    AS capacidad_vehiculo,
    cc.maximum_weight              AS capacidad_maxima,
    -- ─── Trailer ───
    tr.placa                       AS placa_trailer,
    cct_tr.name                    AS tipo_trailer,
    tls_tr.name                    AS marca_trailer,
    ccl_tr.name                    AS color_trailer,
    tr.empty_weight                AS peso_vacio,
    tr.model                       AS modelo_trailer,
    tr.num_axes                    AS numero_ejes,
    -- ─── Conductor ─── [P3: datos personales]
    dct_pp_dv.name                 AS tipo_documento_conductor,
    pp_dv.document                 AS documento_conductor,
    pp_dv.name || ' ' || COALESCE(pp_dv.secondname,'')  AS nombre_conductor,
    pp_dv.lastname || ' ' || COALESCE(pp_dv.lastname2,'') AS apellido_conductor,
    pp_dv.address                  AS direccion_conductor,
    ct_pp_dv.name                  AS ciudad_conductor,
    dpt_pp_dv.name                 AS dpto_conductor,
    pp_dv.phone                    AS telefono_conductor,
    pp_dv.email                    AS correo_conductor,
    pp_dv.emergency_contact_name   AS contacto_emergencia,
    pp_dv.emergency_contact_phone  AS telefono_emergencia,       -- [B3]
    pp_dv.emergency_contact_address AS direccion_emergencia,
    pp_dv.expedition_date          AS fecha_expedicion,
    pp_dv.due_date                 AS fecha_vigencia,
    pp_dv.referencephone1          AS movil_referencia1,
    pp_dv.referencephone2          AS movil_referencia2,
    pp_dv.referencephone3          AS movil_referencia3,
    -- ─── Propietarios y tenedores ─── [P3]
    ppow_vh.document               AS documento_propietario_vehiculo,
    ppow_vh.checkdigit             AS digito_propietario_vehiculo,
    ppow_vh.name || ' ' || COALESCE(ppow_vh.secondname,'')   AS nombre_propietario_vehiculo,
    ppow_vh.lastname || ' ' || COALESCE(ppow_vh.lastname2,'') AS apellido_propietario_vehiculo,
    pphd_vh.document               AS documento_tenedor_vehiculo,   -- [A6]
    pphd_vh.name || ' ' || COALESCE(pphd_vh.lastname,'')      AS tenedor_vehiculo,
    ppow_tr.document               AS documento_propietario_trailer,
    ppow_tr.checkdigit             AS digito_propietario_trailer,
    ppow_tr.name || ' ' || COALESCE(ppow_tr.secondname,'')   AS nombre_propietario_trailer,
    ppow_tr.lastname || ' ' || COALESCE(ppow_tr.lastname2,'') AS apellido_propietario_trailer,
    pphd_tr.document               AS documento_tenedor_trailer,    -- [A6]
    pphd_tr.name || ' ' || COALESCE(pphd_tr.lastname,'')      AS tenedor_trailer,
    -- ─── Negocio y cliente ───
    bc.name                        AS cliente,
    bst.name                       AS tipo_negocio,
    bs.description                 AS descripcion_negocio,
    lg.name || ' ' || COALESCE(lg.lastname,'') AS generador_carga,
    dct.name                       AS tipo_identificacion,
    lg.document                    AS documento,
    lg.checkdigit                  AS digito_verificacion,
    lg.address                     AS direccion_generador,
    lg.phone                       AS telefono_generador,
    lg.mobile                      AS movil_generador,
    ct_lg.name                     AS ciudad_generador,
    dpt_lg.name                    AS departamento_generador,
    lg_paga.name || ' ' || COALESCE(lg_paga.lastname,'') AS cliente_paga,   -- [A3][P1]
    rq.name_receives               AS quien_recibe,                          -- [A4]
    rq.phone_receives              AS telefono_recibe,
    rq.another_receives            AS otro_recibe,
    -- ─── Booking / solicitud ───
    bt.name                        AS tipo_booking,
    st.name                        AS tipo_sider,
    dt.name                        AS tipo_distribucion,
    bk.date                        AS fecha_booking,
    bk.created_at                  AS fecha_reserva,
    bk.freight_driver              AS flete_conductor,
    bk.freight_company             AS flete_empresa,
    rq.loading_date                AS fecha_carga_solicitud,
    rq.freight                     AS flete,
    rq.created_at                  AS fecha_solicitud,
    rq.updated_at                  AS actualizacion_solicitud,
    rq.value_pay_to_driver         AS valor_pago,
    rq.load_weight                 AS peso,
    rq.load_units                  AS unidades,
    rq.ingresoid_remesa            AS rndc_remesa,
    rq.ingresoid_manifest          AS rndc_manifiesto,
    rq.seguridadqr_manifest        AS seguridad_qr,
    rq.sinister                    AS siniestrado,
    rq.standby                     AS standby,
    rq.exclusive_fleet             AS flete_exclusivo,
    rq.seconds_to_destiny          AS segundos_destino,
    rq.has_auction                 AS tiene_auction,
    rq.load_id                     AS load_id,
    rq.trip_id                     AS trip_id,
    rq.operation_type              AS tipo_operacion,
    tc.name                        AS empresa_despacha,
    -- ─── Ruta ───
    c_origen.name                  AS origen,
    pl.planta_origen                AS planta_origen,              -- [A8]
    c_destino.name                  AS destino,
    pl.planta_destino               AS planta_destino,             -- [A8]
    ptr_br.name                    AS ruta,
    prod.productos_codigo          AS productos,
    -- ─── Eventos y novedades ───
    fc.fecha_fin_cargue            AS fecha_fin_cargue,           -- [A5]
    (fc.request_id IS NOT NULL)    AS reporto_fin_cargue,         -- [A5]
    nov.ultimo_estado_novedad      AS ultimo_estado_novedad,
    nov.estados_novedad            AS novedad,
    nov.fecha_ultima_novedad       AS fecha_novedad,
    -- json_agg devuelve json, no jsonb (ver docs/verificacion-tipos-destino-v4.md
    -- ⚠️ eventos_detalle): se castea explicitamente para que el driver JDBC
    -- entregue el mismo texto que espera la columna destino (jsonb).
    CAST(ej.eventos_detalle AS jsonb) AS eventos_detalle,         -- [A7]
    -- ─── Cumplidos ─── [A1]
    cum.cumplidos_total, cum.cumplidos_ok, cum.cumplidos_anulados,
    cum.fecha_ultimo_cumplido, cum.viaje_cumplido,
    -- ─── Liquidación y anticipos ─── [A2]
    liq.liquidacion_consecutivo, liq.anticipos_girados, liq.saldo_liquidacion,
    liq.deducciones, liq.valor_acordado_conductor,
    liq.liquidacion_pagada, liq.liquidacion_legalizada, liq.liquidacion_anulada
FROM requests rq
JOIN req_window w              ON w.id = rq.id                        -- ventana (único filtro)
LEFT JOIN bookings bk          ON bk.id = rq.booking_id               -- [B6] grano = solicitud
LEFT JOIN bigcustomers bc      ON bc.id = bk.bigcustomer_id
LEFT JOIN businesses bs        ON bs.id = bk.business_id
LEFT JOIN businesstypes bst    ON bst.id = bs.businesstype_id
LEFT JOIN businessroutes br    ON br.id = bk.businessroute_id         -- [B1] CORREGIDO
LEFT JOIN pathroutes ptr_br    ON ptr_br.id = br.pathroute_id
LEFT JOIN routes ro            ON ro.id = ptr_br.route_id
LEFT JOIN cities c_origen      ON c_origen.id = ro.from_city_id
LEFT JOIN cities c_destino     ON c_destino.id = ro.to_city_id
LEFT JOIN carconfigs cc        ON cc.id = bk.carconfig_id
LEFT JOIN productos prod       ON prod.business_id = bk.business_id
LEFT JOIN cartypes ct          ON ct.id = bk.cartype_id
LEFT JOIN sider_types st       ON st.id = bk.sider_type_id
LEFT JOIN booking_types bt     ON bt.id = bk.booking_type_id
LEFT JOIN distribution_types dt ON dt.id = bk.distribution_type_id
LEFT JOIN loadgenerators lg    ON lg.id = rq.loadgenerator_id
LEFT JOIN departments dpt_lg   ON dpt_lg.id = lg.department_id
LEFT JOIN cities ct_lg         ON ct_lg.id = lg.city_id
LEFT JOIN doctypes dct         ON dct.id = lg.doctype_id
LEFT JOIN loadgenerators lg_paga ON lg_paga.id = bk.paga_id           -- [A3][P1] tentativo
LEFT JOIN requeststatuses rt_rq ON rt_rq.id = rq.requeststatus_id
LEFT JOIN people pp_dv         ON pp_dv.id = rq.driver_id
LEFT JOIN doctypes dct_pp_dv   ON dct_pp_dv.id = pp_dv.doctype_id
LEFT JOIN departments dpt_pp_dv ON dpt_pp_dv.id = pp_dv.department_id
LEFT JOIN cities ct_pp_dv      ON ct_pp_dv.id = pp_dv.city_id
LEFT JOIN vehicles vh          ON vh.id = rq.vehicle_id
LEFT JOIN carmarks ck          ON ck.id = vh.carmark_id
LEFT JOIN carlines cls         ON cls.id = vh.carline_id
LEFT JOIN carcolors ccl        ON ccl.id = vh.carcolor_id
LEFT JOIN cartypes cct_vh      ON cct_vh.id = vh.cartype_id
LEFT JOIN people ppow_vh       ON ppow_vh.id = vh.owner_id
LEFT JOIN people pphd_vh       ON pphd_vh.id = vh.holder_id
LEFT JOIN trailers tr          ON tr.id = rq.trailer_id
LEFT JOIN trailermarks tls_tr  ON tls_tr.id = tr.trailermark_id
LEFT JOIN carcolors ccl_tr     ON ccl_tr.id = tr.carcolor_id
LEFT JOIN cartypes cct_tr      ON cct_tr.id = tr.cartype_id
LEFT JOIN people ppow_tr       ON ppow_tr.id = tr.owner_id
LEFT JOIN people pphd_tr       ON pphd_tr.id = tr.holder_id
LEFT JOIN transport_companies tc ON tc.id = rq.transport_company_id
LEFT JOIN novedades nov        ON nov.request_id = rq.id
LEFT JOIN eventos_json ej      ON ej.request_id = rq.id               -- [A7]
LEFT JOIN fin_cargue fc        ON fc.request_id = rq.id
LEFT JOIN ministries cm        ON cm.request_id = rq.id               -- [B7]
LEFT JOIN cumplidos cum        ON cum.request_id = rq.id              -- [A1]
LEFT JOIN liquidacion liq      ON liq.request_id = rq.id              -- [A2]
LEFT JOIN plantas pl           ON pl.booking_id = bk.id -- [A8]
;
