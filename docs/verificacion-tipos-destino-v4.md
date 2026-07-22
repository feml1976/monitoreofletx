# Verificación de tipos — columnas del SELECT en `monitoreofletx-consulta-base-v4.sql`

> Gate de viabilidad de tipos, ejecutado por Claude Desktop contra Fletx real (`pg-fletx-aurora-prod`, 2026-07-22), previo a la migración V2 (tabla de destino) de la Etapa B. Cubre las ~30 tablas referenciadas en el SELECT/JOIN de la v4. Objetivo: que la migración V2 y el `RowMapper` se escriban con tipos confirmados, no supuestos — evita tener que modificar una migración ya aplicada.

**Convención de tipos destino:** `bigint`→`Long`, `integer`→`Integer`, `boolean`→`Boolean`, `varchar(n)`/`text`→`String`, `timestamp`→`timestamp without time zone` (Java `LocalDateTime`, nunca `Instant`: Fletx no guarda zona), `date`→`date` (Java `LocalDate`), `numeric`→`numeric` (Java `BigDecimal`), JSON→`jsonb` (Java `String` crudo, cast `::jsonb` en el INSERT).

## ⚠️ Hallazgos que rompen el supuesto "obvio" — leer antes de escribir el `RowMapper`

| Columna destino | Fuente | Tipo real en Fletx | Por qué sorprende |
|---|---|---|---|
| `peso_vacio` | `trailers.empty_weight` | **`character varying`** | Es texto, no numérico — a diferencia de `vehicles.empty_weight` que sí es `integer`. No castear a número sin `TRY_CAST`/validación; recomendado: destino `varchar`, tal cual. |
| `standby` | `requests.standby` | **`integer`** | No confundir con `businesses.standby` que sí es `boolean`. El campo del request es un contador/código, no un flag. |
| `flete` | `requests.freight` | **`bigint`** | `requests.value_pay_to_driver` es `numeric`, `bookings.freight_driver/freight_company` son `numeric` — pero este es `bigint`. Tres columnas de "plata" con tres tipos distintos; no asumir un tipo común. |
| `documento_conductor`, `documento_propietario_*`, `documento_tenedor_*`, `documento` (generador) | `people.document`, `loadgenerators.document` | **`bigint`** | Las cédulas/NIT viven como número, no como texto. Sin ceros a la izquierda posibles (consistente con el origen). Destino: `bigint`, Java `Long` — no `String`. |
| `movil_referencia1/2/3` | `people.referencephone1/2/3` | **`bigint`** | Igual que arriba: numérico, no texto. |
| `eventos_detalle` | `json_agg(...)` | Postgres devuelve `json` (no `jsonb`) por defecto | Castear explícitamente a `jsonb` en el SELECT (`eventos_detalle::jsonb`) o en el `INSERT` del datamart — la columna destino debe ser `jsonb` para permitir indexar a futuro (GIN) si Analítica lo pide. |
| `otro_recibe` | `requests.another_receives` | `boolean` | Es un flag ("¿alguien más recibe?"), no el nombre de quien recibe (eso es `quien_recibe`/`name_receives`). Nombrado de forma confusa en origen — documentarlo así en el Javadoc del record. |

## Identificadores

| Columna destino | Fuente (tabla.columna) | Tipo Fletx | Nullable origen | Tipo destino / Java |
|---|---|---|---|---|
| `reserva_booking` | `bookings.id` | bigint | NO | bigint / Long |
| `solicitud_request` | `requests.id` | bigint | NO | **bigint NOT NULL UNIQUE — clave natural** / Long |
| `estado_solicitud` | `requeststatuses.name` | varchar | YES | varchar / String |
| `descripcion_estado` | `requeststatuses.description` | varchar | YES | varchar / String |
| `remesa` | `consecutive_ministries.consecutive_remesa` | bigint | YES | bigint / Long |
| `manifiesto` | `consecutive_ministries.consecutive_manifest` | bigint | YES | bigint / Long |

## Vehículo

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `placa_vehiculo` | `vehicles.placa` | varchar | varchar / String |
| `marca_vehiculo` | `carmarks.name` | varchar | varchar / String |
| `modelo` | `carlines.value` | varchar | varchar / String |
| `color_vehiculo` | `carcolors.name` | varchar | varchar / String |
| `tipo_carroceria` | `cartypes.name` (vía `vh.cartype_id`) | varchar | varchar / String |
| `afiliacion_vehiculo` | `CASE` sobre `vehicles.is_owner` (boolean) + `is_affiliate` (integer) | — | varchar / String (`'Propio'/'Afiliado'/'Tercero'`) |
| `configuracion_vehiculo` | `carconfigs.description` | varchar | varchar / String |
| `tipo_vehiculo` | `cartypes.name` (vía `bk.cartype_id`) | varchar | varchar / String |
| `capacidad_vehiculo` | `carconfigs.capacity` | integer | integer / Integer |
| `capacidad_maxima` | `carconfigs.maximum_weight` | integer | integer / Integer |

## Trailer

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `placa_trailer` | `trailers.placa` | varchar | varchar / String |
| `tipo_trailer` | `cartypes.name` (vía `tr.cartype_id`) | varchar | varchar / String |
| `marca_trailer` | `trailermarks.name` | varchar | varchar / String |
| `color_trailer` | `carcolors.name` (vía `tr.carcolor_id`) | varchar | varchar / String |
| `peso_vacio` | `trailers.empty_weight` | **varchar** ⚠️ | varchar / String (no numérico) |
| `modelo_trailer` | `trailers.model` | integer NOT NULL | integer / Integer |
| `numero_ejes` | `trailers.num_axes` | integer | integer / Integer |

## Conductor (`people pp_dv`) — datos personales [P3]

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `tipo_documento_conductor` | `doctypes.name` | varchar | varchar / String |
| `documento_conductor` | `people.document` | **bigint** ⚠️ | bigint / Long |
| `nombre_conductor` | `people.name \|\| ' ' \|\| secondname` | varchar (concat) | varchar / String |
| `apellido_conductor` | `people.lastname \|\| ' ' \|\| lastname2` | varchar (concat) | varchar / String |
| `direccion_conductor` | `people.address` | varchar | varchar / String |
| `ciudad_conductor` | `cities.name` | varchar | varchar / String |
| `dpto_conductor` | `departments.name` | varchar | varchar / String |
| `telefono_conductor` | `people.phone` | varchar | varchar / String |
| `correo_conductor` | `people.email` | varchar | varchar / String |
| `contacto_emergencia` | `people.emergency_contact_name` | varchar | varchar / String |
| `telefono_emergencia` | `people.emergency_contact_phone` | varchar | varchar / String |
| `direccion_emergencia` | `people.emergency_contact_address` | varchar | varchar / String |
| `fecha_expedicion` | `people.expedition_date` | date | date / LocalDate |
| `fecha_vigencia` | `people.due_date` | date | date / LocalDate |
| `movil_referencia1` | `people.referencephone1` | **bigint** ⚠️ | bigint / Long |
| `movil_referencia2` | `people.referencephone2` | **bigint** ⚠️ | bigint / Long |
| `movil_referencia3` | `people.referencephone3` | **bigint** ⚠️ | bigint / Long |

## Propietarios y tenedores [P3]

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `documento_propietario_vehiculo` | `people.document` (`ppow_vh`) | bigint | bigint / Long |
| `digito_propietario_vehiculo` | `people.checkdigit` | integer | integer / Integer |
| `nombre_propietario_vehiculo` | `people.name \|\| ' ' \|\| secondname` | varchar | varchar / String |
| `apellido_propietario_vehiculo` | `people.lastname \|\| ' ' \|\| lastname2` | varchar | varchar / String |
| `documento_tenedor_vehiculo` | `people.document` (`pphd_vh`) | bigint | bigint / Long |
| `tenedor_vehiculo` | `people.name \|\| ' ' \|\| lastname` | varchar | varchar / String |
| `documento_propietario_trailer` | `people.document` (`ppow_tr`) | bigint | bigint / Long |
| `digito_propietario_trailer` | `people.checkdigit` | integer | integer / Integer |
| `nombre_propietario_trailer` | `people.name \|\| ' ' \|\| secondname` | varchar | varchar / String |
| `apellido_propietario_trailer` | `people.lastname \|\| ' ' \|\| lastname2` | varchar | varchar / String |
| `documento_tenedor_trailer` | `people.document` (`pphd_tr`) | bigint | bigint / Long |
| `tenedor_trailer` | `people.name \|\| ' ' \|\| lastname` | varchar | varchar / String |

## Negocio y cliente

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `cliente` | `bigcustomers.name` | varchar | varchar / String |
| `tipo_negocio` | `businesstypes.name` | varchar | varchar / String |
| `descripcion_negocio` | `businesses.description` | varchar | varchar / String |
| `generador_carga` | `loadgenerators.name \|\| ' ' \|\| lastname` | varchar | varchar / String |
| `tipo_identificacion` | `doctypes.name` (vía `lg.doctype_id`) | varchar | varchar / String |
| `documento` | `loadgenerators.document` | **bigint** ⚠️ | bigint / Long |
| `digito_verificacion` | `loadgenerators.checkdigit` | integer | integer / Integer |
| `direccion_generador` | `loadgenerators.address` | varchar | varchar / String |
| `telefono_generador` | `loadgenerators.phone` | varchar | varchar / String |
| `movil_generador` | `loadgenerators.mobile` | varchar | varchar / String |
| `ciudad_generador` | `cities.name` (vía `lg.city_id`) | varchar | varchar / String |
| `departamento_generador` | `departments.name` (vía `lg.department_id`) | varchar | varchar / String |
| `cliente_paga` | `loadgenerators.name \|\| ' ' \|\| lastname` (vía `bk.paga_id`) [P1] | varchar | varchar / String |
| `quien_recibe` | `requests.name_receives` | varchar | varchar / String |
| `telefono_recibe` | `requests.phone_receives` | varchar | varchar / String |
| `otro_recibe` | `requests.another_receives` | **boolean** ⚠️ (ver nota arriba) | boolean / Boolean |

## Booking / solicitud

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `tipo_booking` | `booking_types.name` | varchar | varchar / String |
| `tipo_sider` | `sider_types.name` | varchar | varchar / String |
| `tipo_distribucion` | `distribution_types.name` | varchar | varchar / String |
| `fecha_booking` | `bookings.date` | timestamp | timestamp / LocalDateTime |
| `fecha_reserva` | `bookings.created_at` | timestamp NOT NULL | timestamp / LocalDateTime |
| `flete_conductor` | `bookings.freight_driver` | numeric | numeric / BigDecimal |
| `flete_empresa` | `bookings.freight_company` | numeric | numeric / BigDecimal |
| `fecha_carga_solicitud` | `requests.loading_date` | timestamp | timestamp / LocalDateTime |
| `flete` | `requests.freight` | **bigint** ⚠️ | bigint / Long |
| `fecha_solicitud` | `requests.created_at` | timestamp NOT NULL | timestamp / LocalDateTime |
| `actualizacion_solicitud` | `requests.updated_at` | timestamp NOT NULL | timestamp / LocalDateTime |
| `valor_pago` | `requests.value_pay_to_driver` | numeric | numeric / BigDecimal |
| `peso` | `requests.load_weight` | integer | integer / Integer |
| `unidades` | `requests.load_units` | integer | integer / Integer |
| `rndc_remesa` | `requests.ingresoid_remesa` | varchar | varchar / String |
| `rndc_manifiesto` | `requests.ingresoid_manifest` | varchar | varchar / String |
| `seguridad_qr` | `requests.seguridadqr_manifest` | varchar | varchar / String |
| `siniestrado` | `requests.sinister` | boolean | boolean / Boolean |
| `standby` | `requests.standby` | **integer** ⚠️ (no boolean) | integer / Integer |
| `flete_exclusivo` | `requests.exclusive_fleet` | boolean | boolean / Boolean |
| `segundos_destino` | `requests.seconds_to_destiny` | bigint | bigint / Long |
| `tiene_auction` | `requests.has_auction` | boolean | boolean / Boolean |
| `load_id` | `requests.load_id` | bigint | bigint / Long |
| `trip_id` | `requests.trip_id` | bigint | bigint / Long |
| `tipo_operacion` | `requests.operation_type` | varchar | varchar / String |
| `empresa_despacha` | `transport_companies.name` | varchar | varchar / String |

## Ruta

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `origen` | `cities.name` (`from_city_id`) | varchar | varchar / String |
| `planta_origen` | `STRING_AGG(addresses.name)` (CTE `plantas`) | text (agregado) | text / String |
| `destino` | `cities.name` (`to_city_id`) | varchar | varchar / String |
| `planta_destino` | `STRING_AGG(addresses.name)` (CTE `plantas`) | text (agregado) | text / String |
| `ruta` | `pathroutes.name` | varchar | varchar / String |
| `productos` | `STRING_AGG(productcodes.value)` (CTE `productos`) | text (agregado) | text / String |

## Eventos y novedades

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `fecha_fin_cargue` | `MAX(events.created_at)` (CTE `fin_cargue`, filtra `requeststatus_id = 7`) | timestamp (agregado) | timestamp / LocalDateTime |
| `reporto_fin_cargue` | `fin_cargue.request_id IS NOT NULL` | boolean (computado) | boolean / Boolean |
| `ultimo_estado_novedad` | `ARRAY_AGG(requeststatuses.name)[1]` (CTE `novedades`) | varchar (agregado) | varchar / String |
| `novedad` | `STRING_AGG(requeststatuses.name, ' -> ')` (CTE `novedades`) | text (agregado) | text / String |
| `fecha_novedad` | `MAX(events.created_at)` (CTE `novedades`) | timestamp (agregado) | timestamp / LocalDateTime |
| `eventos_detalle` | `json_agg(json_build_object(...))` (CTE `eventos_json`) | **json** ⚠️ (castear a jsonb) | **jsonb** / String crudo |

## Cumplidos [A1]

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `cumplidos_total` | `COUNT(*)` sobre `comply_destinations` | bigint (agregado) | bigint / Long |
| `cumplidos_ok` | `COUNT(*) FILTER (accomplished)` | bigint (agregado) | bigint / Long |
| `cumplidos_anulados` | `COUNT(*) FILTER (annulled)` | bigint (agregado) | bigint / Long |
| `fecha_ultimo_cumplido` | `MAX(comply_destinations.date_comply)` | timestamp (agregado) | timestamp / LocalDateTime |
| `viaje_cumplido` | `BOOL_AND(accomplished) FILTER (NOT annulled)` | boolean (agregado) | boolean / Boolean |

## Liquidación y anticipos [A2]

| Columna destino | Fuente | Tipo Fletx | Tipo destino / Java |
|---|---|---|---|
| `liquidacion_consecutivo` | `liquidations.consecutive` | bigint | bigint / Long |
| `anticipos_girados` | `liquidations.advance` | bigint | bigint / Long |
| `saldo_liquidacion` | `liquidations.balance` | bigint | bigint / Long |
| `deducciones` | `liquidations.deductions` | bigint | bigint / Long |
| `valor_acordado_conductor` | `liquidations.agreed_value_to_pay_driver` | bigint | bigint / Long |
| `liquidacion_pagada` | `liquidations.was_paid` | boolean | boolean / Boolean |
| `liquidacion_legalizada` | `liquidations.legalized` | boolean | boolean / Boolean |
| `liquidacion_anulada` | `liquidations.date_annulled IS NOT NULL` | boolean (computado) | boolean / Boolean |

## Housekeeping (agregar en el destino, no vienen de Fletx)

| Columna destino | Tipo | Nota |
|---|---|---|
| `row_hash` | varchar(32) NOT NULL | MD5 en Java (nunca `md5()` de SQL), mismo patrón que Controlt |
| `deleted_at` | timestamp NULL | soft-delete |
| `sincronizado_at` | timestamp NOT NULL DEFAULT now() | housekeeping propio del job (no confundir con `fecha_solicitud`/`actualizacion_solicitud`, que son de Fletx) |

## Pendientes que NO bloquean la migración V2 pero sí el criterio de aceptación final

- **[P1]** `cliente_paga` vía `bookings.paga_id → loadgenerators.id`: el JOIN es tentativo (mismo patrón que `emite_id`/`recibe_id`, todos apuntan a `loadgenerators`, consistente). No se detectó anomalía de tipos, pero falta confirmación semántica con el solicitante.
- **[P2]** Remitente/destinatario RNDC (`freight_ministries`) — tabla aún no incorporada al SELECT.
- **[P3]** Ley 1581 — esta verificación no cambia el inventario de campos con datos personales, solo confirma sus tipos.
