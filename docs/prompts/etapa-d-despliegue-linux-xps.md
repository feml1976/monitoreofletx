# Runbook — Etapa D: Ejecución contra Fletx real y despliegue en `Linux-XPS`

> **Uso:** guía de operación para el servidor `Linux-XPS` (mismo servidor que ya corre Controlt). Puede ejecutarla Claude Code si tiene acceso SSH al servidor; si no, ejecución manual copiando los bloques de comandos.
> **Referencia:** `REQUERIMIENTO.md` §7/§8, `docs/plan-produccion-monitoreofletx.md` §Etapa D. Etapas A–C aprobadas (commits hasta `479c6c2`, 66/66 tests verdes).
> **A diferencia de Controlt (`docs/prompts/etapa-3-despliegue-linux-xps.md`):** el servidor YA está preparado (BIOS con AC Recovery, mitigación NIC r8169, timezone `America/Bogota`, Docker como servicio) — eso no se repite aquí. Tampoco hay que decidir JAR plano vs. build en servidor: el CI/CD nace desde la Etapa A, así que la imagen ya se publica en GHCR por tag.

---

## Contexto clave antes de empezar

- Deploy key SSH read-only ya creada en el servidor para este repo (fingerprint `SHA256:wqZYFlJDN0KH1IJXMoZ+tzn7y0pWHpqiCZ01WYfDIV0`).
- Schema `monitoreo_fletx` ya creado por el DBA en `datarmart01` (confirmado).
- **Esta es la primera vez que las migraciones V1/V2 se aplican contra una BD real** (a diferencia del runbook de Controlt, donde ya se habían aplicado en la etapa anterior) — Flyway debe *crear* las tablas aquí, no solo confirmar "up to date". Si algo falla en esta migración inicial, es más barato corregirlo ahora que después de tener datos.
- Puerto de management: **8098**. Nunca 8097 (Controlt, ya corriendo en este mismo servidor).

## Reglas duras (heredadas)

1. TLS obligatorio en ambas conexiones (`sslmode=require`) — ya verificado que Fletx y `datarmart01` soportan TLS sin cambios de servidor (mismo hallazgo que validó Controlt).
2. `.env` se escribe **directamente en el servidor**, nunca por chat/correo/repo. Ni Claude Code ni Claude Desktop lo leen ni lo imprimen.
3. Usuario de origen: solo SELECT, verificado antes de conectar en producción (no asumir que el usuario de Controlt sirve aquí sin confirmar sus grants sobre las tablas nuevas del dataset — `liquidations`, `comply_destinations`, `booking_addresses`, `addresses`, `consecutive_ministries`).
4. No tocar la instancia de Controlt (contenedor, `.env`, red) — son aplicaciones hermanas independientes, no comparten proceso ni configuración, solo servidor y origen de datos.

---

## Paso 1 — ✅ Grants del usuario de origen (ya verificado 2026-07-22)

Confirmado vía conector de solo lectura: el rol de origen ya en uso por Controlt tiene `SELECT` — y únicamente `SELECT`, sin `INSERT`/`UPDATE`/`DELETE` — sobre las 7 tablas nuevas de este dataset: `liquidations`, `comply_destinations`, `booking_addresses`, `addresses`, `consecutive_ministries`, `businessproducts`, `productcodes`. No es superusuario. **No se requiere ningún GRANT nuevo**; usa el mismo rol/credenciales que Controlt para `ORIGIN_DB_*`.

## Paso 2 — Llevar el proyecto al servidor y traer la imagen

```bash
git clone git@github-monitoreofletx:feml1976/monitoreofletx.git ~/monitoreofletx && cd ~/monitoreofletx
# (usa el alias de host de la deploy key, no github.com directo)

# Imagen publicada por release.yml en el primer tag (ej. v0.1.0):
docker login ghcr.io -u feml1976   # con el mismo PAT read:packages que ya usa Controlt
docker pull ghcr.io/feml1976/monitoreofletx:v0.1.0
docker tag ghcr.io/feml1976/monitoreofletx:v0.1.0 monitoreofletx:latest
```

Si no existe todavía un tag/release: crear uno (`git tag v0.1.0 && git push origin v0.1.0`) y esperar a que `release.yml` publique la imagen antes de continuar.

## Paso 3 — `.env` de producción

```bash
cd ~/monitoreofletx
cp .env.example .env
nano .env
chmod 600 .env
ls -l .env   # verificar: -rw------- y owner correcto
```

Variables requeridas (sin URLs compuestas — host/puerto/BD separados, ver `REQUERIMIENTO.md` §8):
`ORIGIN_DB_HOST/PORT/NAME/USERNAME/PASSWORD`, `ORIGIN_DB_SCHEMA=public`, `ORIGIN_DB_SSLMODE=require`,
`DATAMART_DB_HOST/PORT/NAME/USERNAME/PASSWORD`, `DATAMART_DB_SCHEMA=monitoreo_fletx`, `DATAMART_DB_SSLMODE=require`,
`SYNC_VENTANA_DIAS=7`, `SYNC_CRON=0 0/15 * * * *`, `MANAGEMENT_PORT=8098`.

## Paso 4 — Arranque y verificación de migraciones

```bash
cd ~/monitoreofletx
docker compose -f docker-compose.prod.yml up -d
docker logs -f monitoreofletx-monitoreofletx-1
```

Esperado: Flyway aplica **V1 (shedlock) y V2 (tabla `solicitudes` completa)** por primera vez — deben verse ambas migraciones en el log, sin errores. Si V2 falla, **detente** (no reintentar a ciegas — revisar contra `docs/verificacion-tipos-destino-v4.md` antes de reintentar).

```bash
curl -s http://127.0.0.1:8098/actuator/health | grep -o '"status":"[A-Z]*"' | head -3   # UP, ambos DataSources
curl -s http://127.0.0.1:8098/actuator/prometheus | grep monitoreofletx_sync | head -5
```

## Paso 5 — Primer ciclo real: conteos cruzados (lo hace Claude Desktop)

No lo ejecuta Claude Code. Tras el primer ciclo (máx. 15 min), Claude Desktop valida vía conector de solo lectura:

```sql
-- Contra datarmart01
SELECT count(*) FROM monitoreo_fletx.solicitudes WHERE deleted_at IS NULL;
SELECT max(sincronizado_at) FROM monitoreo_fletx.solicitudes;

-- Contra Fletx, misma ventana
SELECT count(*) FROM requests WHERE created_at >= now() - interval '7 days';
```

Los conteos deben cuadrar (±few, por el desfase entre el momento de cada consulta). **0 duplicados de `solicitud_request`** — verificar con `SELECT solicitud_request, count(*) FROM monitoreo_fletx.solicitudes GROUP BY 1 HAVING count(*) > 1;` (debe ser vacío, hay índice único pero verificar de todas formas).

## Paso 6 — Verificaciones de seguridad y convivencia con Controlt

```bash
# 1. Sin puertos nuevos expuestos a internet
ss -tlnp | grep -v "127.0.0.1\|::1"     # no debe aparecer 8098; solo SSH y lo que Controlt ya tenía

# 2. Controlt sigue intacto
docker ps   # ambos contenedores (controlt y monitoreofletx) Up, healthy, independientes

# 3. Rotación de logs y límite de memoria (configurados en el compose desde la Etapa A)
docker inspect monitoreofletx-monitoreofletx-1 --format '{{.HostConfig.LogConfig}}'
docker inspect monitoreofletx-monitoreofletx-1 --format '{{.HostConfig.Memory}}'
```

No es necesario repetir la prueba de corte de energía (ya validada para el servidor completo en la Etapa 3 de Controlt — es una propiedad del servidor, no de la aplicación).

## Paso 7 — Observación 24–48 h

```bash
curl -s http://127.0.0.1:8098/actuator/prometheus | grep -E "monitoreofletx_sync_solicitudes"
docker logs monitoreofletx-monitoreofletx-1 2>&1 | grep -iE "error|exception" | tail -20
```

Y en `datarmart01`: `SELECT MAX(sincronizado_at) FROM monitoreo_fletx.solicitudes;` no debe atrasarse más de 15–20 min. Verificar `monitoreo_fletx.shedlock` sin locks colgados (`lock_until` en el pasado lejano).

## Paso 8 — Registro y cierre

- Crear `docs/registro-despliegue-monitoreofletx.md` (plantilla: el de Controlt) con fecha, tag de imagen, responsable, resultado de los conteos cruzados del Paso 5.
- Documentar rollback: `docker compose -f docker-compose.prod.yml down && docker tag monitoreofletx:<tag_anterior> monitoreofletx:latest && docker compose -f docker-compose.prod.yml up -d`.

---

## ✅ Checklist de aprobación — Etapa D

- [x] Grants del usuario de origen confirmados sobre las 7 tablas nuevas del dataset (verificado 2026-07-22, ver Paso 1).
- [ ] Imagen publicada en GHCR y corriendo en `Linux-XPS`, puerto 8098, sin colisión con Controlt (8097).
- [ ] Flyway aplicó V1+V2 limpio en la primera ejecución real contra `datarmart01`.
- [ ] Health UP en ambos DataSources; ciclos cada 15 min visibles en logs/métricas.
- [ ] Conteos cruzados Fletx↔datamart cuadran (Claude Desktop); 0 duplicados de clave natural.
- [ ] `.env` con permisos 600; `sslmode=require` en ambas conexiones.
- [ ] Controlt no se vio afectado (contenedor independiente, health UP, sin cambios).
- [ ] 24–48 h sin ciclos fallidos ni locks colgados; `MAX(sincronizado_at)` al día.
- [ ] Despliegue registrado en `docs/registro-despliegue-monitoreofletx.md`; rollback documentado.

## Pendientes que quedan abiertos tras esta etapa (no bloqueantes, van a Etapa E o quedan como deuda documentada)

- `[P1]` FK de `bookings.paga_id`, `[P2]` `freight_ministries` (RNDC), `[P3]` tratamiento de datos personales por campo (Ley 1581).
