# Registro de Despliegue — MonitoreoFletx v0.1.0

> Documento de trazabilidad para cumplimiento. No contiene credenciales; las referencias de conexión viven en el `.env` del servidor (permisos 600, fuera de control de versiones).

## 1. Identificación

| Campo | Valor |
|---|---|
| Sistema | MonitoreoFletx (`com.fml:monitoreofletx`) — radiografía de solicitudes (requests) de Fletx |
| Versión desplegada | `v0.1.0` (imagen `ghcr.io/feml1976/monitoreofletx:v0.1.0`, también etiquetada `:latest`) |
| Flujo | BD origen Fletx (Aurora PostgreSQL, solo lectura) → `datarmart01`, schema dedicado `monitoreo_fletx` |
| Responsable del despliegue | Fmontoya (fmontoya@transer.com.co) |
| Fecha de despliegue en servidor | 2026-07-22 |
| Commits de referencia | Etapas A–C hasta `664c0d2` (docs de grants); primer release `v0.1.0` sobre ese mismo HEAD |

## 2. Infraestructura

| Componente | Detalle |
|---|---|
| Servidor | `feml-server` (alias `Linux-XPS`), mismo servidor que ya corre Controlt |
| Runtime | Docker, compose `docker-compose.prod.yml`, contenedor único (BDs externas) |
| Ejecución | `restart: unless-stopped`, límite de memoria 1 GB, logs json-file rotación 10 MB × 5 |
| Programación | Ciclo de sync cada 15 min (cron `0 0/15 * * * *`), TZ America/Bogota, concurrencia protegida por ShedLock (`lockAtMostFor=PT14M`, `lockAtLeastFor=PT1M`) |
| Obtención del código | Clone vía deploy key SSH read-only (`github-monitoreofletx`, fingerprint `SHA256:wqZYFlJDN0KH1IJXMoZ+tzn7y0pWHpqiCZ01WYfDIV0`) |
| Imagen | Publicada por `release.yml` al crear el tag `v0.1.0` (primer release del repo); pull en el servidor desde GHCR, sin build local |

## 3. Verificaciones de seguridad

| Control | Estado | Evidencia |
|---|---|---|
| Cifrado en tránsito | ✅ | `sslmode=require` en ambas conexiones (confirmado en log de Flyway: `jdbc:postgresql://.../datarmart01?sslmode=require`) |
| Menor privilegio — origen | ✅ | Rol de origen (mismo que usa Controlt) verificado con `SELECT` únicamente sobre las 7 tablas nuevas del dataset (`liquidations`, `comply_destinations`, `booking_addresses`, `addresses`, `consecutive_ministries`, `businessproducts`, `productcodes`) — Paso 1 del runbook, 2026-07-22 |
| Credenciales | ✅ | `.env` del servidor con permisos 600, owner correcto; escrito directamente en el servidor por el responsable, nunca leído ni impreso por Claude Code/Desktop |
| Superficie de red | ✅ | Actuator 8098 enlazado solo a `127.0.0.1`; `ss -tlnp` confirma que no aparece expuesto a `0.0.0.0`/`::`; sin colisión con Controlt (8097) ni con los demás servicios preexistentes del servidor |
| Convivencia con Controlt | ✅ | Ambos contenedores `Up`/`healthy` de forma independiente tras el despliegue; no se tocó el contenedor, `.env` ni red de Controlt en ningún momento |

## 4. Incidente durante el despliegue (resuelto sin impacto)

**Error de configuración en el primer intento de arranque:** el `.env` colocado en el servidor tenía `DATAMART_DB_SCHEMA=torre_control` (el schema real de Controlt) en vez de `monitoreo_fletx` — residuo de usar el `.env` de Controlt como plantilla.

- **Efecto:** Flyway validó las migraciones de MonitoreoFletx contra el `flyway_schema_history` ya existente en el schema `torre_control` (3 migraciones de Controlt: `torre control`, `shedlock`, `plantas`, instaladas 2026-07-17). Los checksums no coincidieron (contenido de migración distinto) → **Flyway rechazó el arranque por validación fallida y nunca ejecutó ningún DDL contra `torre_control`.**
- **Verificación de que no hubo impacto:** se confirmó por lectura (`information_schema.tables`) que el schema `torre_control` conserva únicamente las tablas propias de Controlt (`torre_control_viajes`, `v_torre_control_vigente`, `shedlock`); el schema `monitoreo_fletx` (nombre literal) existía vacío y disponible, tal como lo había provisionado el DBA.
- **Corrección:** el responsable editó `DATAMART_DB_SCHEMA=monitoreo_fletx` en el `.env` del servidor. Se recreó el contenedor (`docker compose up -d`, detectó el cambio de `.env`) y Flyway migró limpio desde `<< Empty Schema >>` hasta `v2`.
- **Lección:** la validación de checksums de Flyway actuó como red de seguridad real ante un error humano de configuración que, sin ella, habría escrito la tabla `solicitudes` dentro del schema operativo de Controlt.

## 5. Verificación funcional (primer ciclo real)

| Métrica | Valor |
|---|---|
| Arranque del contenedor | 2026-07-22 19:09:17, `Started MonitoreoFletxApplication in 5.046 seconds` |
| Migraciones Flyway | V1 (`crea shedlock`) + V2 (`crea tabla solicitudes`) aplicadas limpio sobre `<< Empty Schema >>` → `v2` (724 ms) |
| Primer ciclo real (`run_id=77391954`, 2026-07-22 19:15:00) | 3.236 leídos / 3.236 insertados / 0 actualizados / 0 omitidos / 0 obsoletos, sin errores |
| Health | `UP` — ambos DataSources (`datamartDataSource`, `originDataSource`), `ssl.status=UP` |
| Duplicados de clave natural (`solicitud_request`) | 0 |
| Frescura de datos (`sincronizado_at`) al momento de la verificación | rezago de ~7 min respecto al reloj del servidor (dentro de la tolerancia de 15–20 min) |
| ShedLock | Lock adquirido/liberado correctamente para el ciclo (`locked_at`/`lock_until` acorde a `lockAtLeastFor=PT1M`); sin locks colgados |
| Errores/excepciones en log desde el arranque | Ninguno |

## 6. Rollback

```bash
cd ~/monitoreofletx
docker compose -f docker-compose.prod.yml down
docker tag ghcr.io/feml1976/monitoreofletx:<tag_anterior> monitoreofletx:latest
docker compose -f docker-compose.prod.yml up -d
```

Las migraciones V1–V2 solo crean objetos (tabla `solicitudes`, `shedlock`, vista `v_monitoreo_fletx_vigente`); no requieren reversión. La proyección es re-generable desde el origen en cualquier momento (es un read model CQRS, no fuente de verdad).

## 7. Pendientes al cierre de este registro

- [ ] **Paso 5 del runbook (conteos cruzados Fletx↔datamart)** — corresponde a Claude Desktop vía conector de solo lectura, no ejecutado por Claude Code. El primer ciclo (3.236 leídos/insertados, 0 duplicados) es evidencia de apoyo, pero no reemplaza esa validación cruzada formal.
- [ ] **Ventana de observación 24–48 h** — este registro solo cubre una verificación puntual a los ~13 minutos del arranque (snapshot de Paso 7: health, métricas, logs, frescura, ShedLock, todo en orden). Falta el checkpoint de +24 h y +48 h antes de dar la Etapa D por cerrada formalmente.
- [ ] `[P1]` FK de `bookings.paga_id`, `[P2]` `freight_ministries` (RNDC), `[P3]` tratamiento de datos personales por campo (Ley 1581) — heredados de la Etapa C, no bloqueantes.
- [ ] Dashboard Grafana / alertas formales — planificado para la Etapa E, no incluido en este despliegue.

## 8. Historial de versiones desplegadas

| Versión | Fecha | Cambios | Evidencia |
|---|---|---|---|
| v0.1.0 | 2026-07-22 | Despliegue inicial en `Linux-XPS` (ETL ventana móvil 7 días, primera ejecución de V1/V2 contra BD real) | Este registro, secciones 1–6. Incidente de configuración de schema detectado y corregido durante el propio despliegue (§4), sin impacto en Controlt ni en datos |

---

*Generado 2026-07-22 tras el despliegue inicial en Linux-XPS. Fuente de evidencia: logs del contenedor, `/actuator/health` y `/actuator/prometheus`, y verificaciones directas de solo lectura sobre `datarmart01` (schema `monitoreo_fletx` y `torre_control`).*
