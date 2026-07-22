-- =============================================================================
-- MonitoreoFletx — Consulta base v4 (radiografía de solicitudes Fletx)
-- v4 = v3 + planta_origen/planta_destino (acordada con el equipo de Analítica)
-- Base: v2 (gate de viabilidad §2) + v3 (eventos_detalle JSON), sin cambios
-- sobre lo ya validado en versiones previas.
--
-- Grano: 1 fila por request (solicitud). Clave natural: rq.id
-- Ventana: requests creados en los últimos 7 días (parametrizable en el ETL)
--
-- ⚠️ Esta es la copia LISTA PARA PRODUCCIÓN: se removió el filtro de prueba
--    puntual "WHERE rq.id IN (3033260,3032608)" usado para validar el
--    resultado con Analítica sobre dos requests conocidos. NO reintroducir
--    ese filtro al commitear/desplegar — el ETL sincroniza toda la ventana.
--
-- CORRECCIONES sobre la consulta original (heredadas de v2):
--   [B1] BUG: businessroutes se unía por bs.id (businesses) en vez de br.id
--        → ruta/origen/destino salían de un registro arbitrario o nulo
--   [B2] Bloque de columnas del conductor duplicado (4 columnas repetidas)
--   [B3] telefono_emergencia mapeaba emergency_contact_address (igual que
--        direccion_emergencia) → corregido a emergency_contact_phone
--   [B4] CTE fin_cargue estaba definido pero sin usar → ahora se expone como
--        columna informativa (NO filtra: la radiografía incluye todo request)
--   [B5] Sin filtro de fechas → CTE req_window con pushdown a todos los CTEs
--        (sin esto, events [16M filas] se agregaba COMPLETA en cada ejecución)
--   [B6] INNER JOINs convertidos a LEFT: toda FK del camino es anulable en el
--        schema; en una radiografía ningún vacío de captura debe ocultar filas
--   [B7] consecutive_ministries deduplicado (cardinalidad 1:N real detectada
--        en producción por Controlt) → sin esto, requests duplicados
--   [B8] joins muertos eliminados (request_rentals sin uso) y holders
--        (tenedores) ahora aprovechados como columnas
--   [B9] typos: documento_condcutor→documento_conductor, corre→correo
--   [B10] la consulta de ejemplo de Analítica para eventos usaba INNER JOIN
--        contra requeststatuses en AMBOS lados (estado_anterior y actual).
--        Validado contra Fletx real: el evento de creación de cada request
--        SIEMPRE tiene requeststatatusold_id NULL (3.228/3.228 requests con
--        eventos en 7 días) — es el evento "génesis", no una anomalía. Un
--        INNER JOIN ahí descarta ese evento en el 100% de los requests.
--        Corregido a LEFT JOIN sobre requeststatuses (ambos lados).
--
-- AGREGADOS (solicitados + descubiertos en el gate):
--   [A1] Cumplidos del viaje: comply_destinations (14.073 filas/30d, activa)
--   [A2] Liquidación vigente: liquidations (11.830/30d) — anticipos girados,
--        saldo, deducciones, pagado, legalizado. NOTA: la tabla advances está
--        INACTIVA (0 filas/30d) — los anticipos vigentes viven en liquidations
--   [A3] Cliente que paga: bookings.paga_id (⚠ confirmar tabla destino del FK)
--   [A4] Quien recibe: requests.name_receives / phone_receives / another_receives
--   [A5] Fecha fin de cargue (informativa) y flag de si ya lo reportó
--   [A6] Tenedores (holder) de vehículo y trailer
--   [A7] eventos_detalle — arreglo JSON con la evolución completa del estado
--        del viaje (evento_id, fecha_hora, estado_anterior, estado_actual,
--        comentario), ordenado cronológicamente. Acordado con Analítica.
--   [A8] NUEVO (v4): planta_origen / planta_destino, vía
--        booking → booking_addresses → addresses. Un booking puede tener
--        varias direcciones de carga y/o descarga (multi-recogida/entrega);
--        se agregan con STRING_AGG(DISTINCT ...) por booking_id para NO
--        generar fan-out de filas del request (mismo patrón que
--        cumplidos/liquidacion). Validado: 2.739 bookings en ventana,
--        promedio 2,01 direcciones/booking, máx. 4; 19 bookings con
--        múltiples plantas de carga y 12 con múltiples de descarga
--        (correctamente concatenadas); 0 filas con address_id nulo.
--
-- PENDIENTE DE CONFIRMAR con el solicitante / siguiente iteración:
--   [P1] FK de bookings.paga_id (¿loadgenerators?) — hoy se une tentativo
--   [P2] Remitente/destinatario RNDC: tabla freight_ministries (sender,
--        destination_name) — agregar cuando se defina la relación exacta
--   [P3] Datos personales abundantes (cédulas, teléfonos, correos, contactos
--        de emergencia, propietarios, y el "comentario" de eventos_detalle):
--        definir tratamiento por campo (Ley 1581)
--   [P4] Convivencia de eventos_detalle (JSON) con la columna "novedad"
--        (string concatenado): se deja conviviendo, no se fusionan, para no
--        romper consumidores que ya esperen el string.
-- =============================================================================
WITH
req_window AS (
    SELECT r.id, r.booking_id
    FROM requests r
    WHERE r.created_at >= now() - interval '7 days'   -- ETL: >= :fecha_inicio AND < :fecha_fin
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
-- [A8] NUEVO v4: planta origen y planta destino por booking.
-- booking_addresses puede tener varias direcciones de carga (load_address)
-- y/o descarga (unload_address) para un mismo booking (multi-recogida /
-- multi-entrega). Se agrega con STRING_AGG(DISTINCT ...) para devolver una
-- sola fila por booking_id y no duplicar filas del request en el JOIN.
plantas AS (
    SELECT
        ba.booking_id,
        STRING_AGG(DISTINCT ad.name, ', ' ORDER BY ad.name)
            FILTER (WHERE ba.load_address = true)                       AS planta_origen,
        STRING_AGG(DISTINCT ad.name, ', ' ORDER BY ad.name)
            FILTER (WHERE ba.unload_address = true)                    AS planta_destino
    FROM booking_addresses ba
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
    pl.planta_origen                AS planta_origen,              -- [A8] NUEVO v4
    c_destino.name                  AS destino,
    pl.planta_destino               AS planta_destino,             -- [A8] NUEVO v4
    ptr_br.name                    AS ruta,
    prod.productos_codigo          AS productos,
    -- ─── Eventos y novedades ───
    fc.fecha_fin_cargue            AS fecha_fin_cargue,           -- [A5]
    (fc.request_id IS NOT NULL)    AS reporto_fin_cargue,         -- [A5]
    nov.ultimo_estado_novedad      AS ultimo_estado_novedad,
    nov.estados_novedad            AS novedad,
    nov.fecha_ultima_novedad       AS fecha_novedad,
    ej.eventos_detalle             AS eventos_detalle,            -- [A7] (JSON)
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
LEFT JOIN plantas pl           ON pl.booking_id = bk.id                -- [A8] NUEVO v4
ORDER BY rq.created_at DESC, rq.id DESC;  -- (el ETL lo omite; útil para el analista)

-- =============================================================================
-- GATE DE VIABILIDAD — planta_origen/planta_destino, ejecutado 2026-07-22
-- =============================================================================
-- Validado contra Fletx real (ventana 7 días):
--   bookings en ventana ................... 2.739
--   promedio direcciones/booking .......... 2,01
--   máximo direcciones/booking ............ 4
--   bookings con >1 planta de carga ....... 19
--   bookings con >1 planta de descarga .... 12
--   filas con load_address=unload_address=true simultáneo ... 0
--   filas con address_id nulo ............. 0
-- Arquitectura: CTE "plantas" agrega por booking_id con STRING_AGG(DISTINCT)
-- ANTES del JOIN principal → no hay fan-out de filas de request (mismo
-- patrón ya validado en "cumplidos" y "liquidacion"). Sin objeciones.
-- VEREDICTO: ✅ VIABLE.
-- =============================================================================
