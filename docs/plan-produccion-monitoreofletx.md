# Plan de Acción — MonitoreoFletx a Producción

| Campo | Valor |
|---|---|
| Proyecto | MonitoreoFletx (`com.fml:monitoreofletx`) — proyección de solo lectura Fletx → datarmart01 (schema `monitoreo_fletx`) |
| Fecha de inicio | 2026-07-22 |
| Entorno destino | Servidor Linux propio con Docker: Dell XPS 8700, hostname `Linux-XPS` (mismo servidor que Controlt) |
| Naturaleza | Segunda instancia de la receta Controlt — repo, contenedor y ciclo de vida propios; mismo origen (Fletx) |
| Referencia | Documento maestro `REQUERIMIENTO.md` (raíz del repo) — gobierna stack, arquitectura, puertos, BD y restricciones |
| Estado base | Repo creado, `REQUERIMIENTO.md` y `monitoreofletx-consulta-base-v4.sql` commiteados y pusheados. Deploy key SSH read-only creada en `Linux-XPS` (fingerprint `SHA256:wqZYFlJDN0KH1IJXMoZ+tzn7y0pWHpqiCZ01WYfDIV0`). Schema `monitoreo_fletx` creado por el DBA en `datarmart01`. |

---

## Resumen ejecutivo

MonitoreoFletx replica la receta validada en producción por Controlt (v1.3.0) para un segundo dataset de Fletx. El gate de viabilidad del dataset (§2 del `REQUERIMIENTO.md`) ya se ejecutó contra Fletx real y dio veredicto ✅ VIABLE, con la consulta base ya acordada con el equipo de Analítica. El objetivo es llegar a producción en una fracción del tiempo de Controlt, reutilizando cada decisión de arquitectura, configuración y operación ya tomada — el plan se comprime de 8 etapas (Controlt) a 5 (A–E).

**Ruta crítica:** Etapa A → B → C → D (producción operando). Etapa E cierra backfill y observabilidad.

Cada etapa termina con un **checklist de aprobación**: no se avanza a la siguiente sin marcar todos los ítems.

---

## Etapa A — Scaffold + CI desde el nacimiento

**Objetivo:** estructura completa del repo, lista para compilar, testear en CI y empaquetar en imagen — sin lógica de negocio todavía (eso es Etapa B/C).

**Tareas:**
1. `pom.xml`, esqueleto de paquetes (`domain/`, `application/port/{in,out}`, `application/service/`, `infrastructure/adapter/{in/scheduler,out/origen,out/proyeccion}`, `infrastructure/config/`), clase `Application` mínima que arranque.
2. `application.yml` con fail-fast (variables críticas sin default) + `application-test.yml`.
3. Migración Flyway V1 (ShedLock) — la tabla de destino completa se difiere a la Etapa B (ver justificación en el prompt de la etapa: evita una migración incompleta que luego haya que modificar).
4. `docker-compose.yml` (dev), `docker-compose.test.yml` (test, PG real puerto 5438), `docker-compose.prod.yml` (job + BDs externas).
5. `Dockerfile` multi-stage, no-root.
6. `.github/workflows/ci.yml` (PG real vía compose + build) y `release.yml` (tag `v*` → GHCR) — **desde el día 1**, a diferencia de Controlt que lo agregó al final.
7. `.claude/CLAUDE.md` + `.claude/commands/` adaptados de Controlt.
8. `.gitignore`, `.dockerignore`, `.env.example`, `README.md`.

**Blast radius:** ninguno — repo nuevo, sin consumidores.

### ✅ Checklist de aprobación — Etapa A — **APROBADA 2026-07-22** (commits 9d3f64a…4f6a3b5, 10 commits)

- [x] CI verde en GitHub Actions: [run 29938568689](https://github.com/feml1976/monitoreofletx/actions/runs/29938568689), job `test-and-build`, 1m48s — verificado directamente (fetch de la página de resultados, no solo el reporte de Claude Code): `Status: Success`.
- [x] `docker compose -f docker-compose.test.yml up -d` + `./mvnw -f backend/pom.xml verify` en verde localmente — 2/2 tests, corrido dos veces sin reiniciar el contenedor (fixtures inmunes al envejecimiento).
- [x] Fail-fast verificado: `application.yml` confirmado (lectura directa) — `HOST/PORT/NAME/USERNAME/PASSWORD` de ambas BD sin default; solo `SCHEMA`/`SSLMODE` tienen default. Correcto incluso más estricto que Controlt (que sí defaultea el puerto).
- [x] `mvnw` commiteado con bit de ejecución 100755 — confirmado (`git ls-files -s`).
- [x] `.env.example` sin ningún valor real — confirmado (solo `COMPLETAR`, `localhost`, puertos, nombres de schema).
- [x] Push a GitHub con CI verde visible.

**Desviaciones aprobadas (todas documentadas en commits):**
1. Puertos de BD sin default — interpretación correcta y más estricta de §9.1; no requiere acción.
2. `.claude/` trackeado salvo `settings.local.json` — sigue §10 del `REQUERIMIENTO.md` al pie de la letra; verificado que `.claude/scheduled_tasks.lock` no quedó trackeado.
3. `src/test/resources/application.yml` sin sufijo de perfil (en vez de `application-test.yml` + activación de perfil) — mejor que lo especificado en el prompt: elimina el riesgo de que CI olvide activar el perfil. Aprobado como mejora.
4. Flyway V1=shedlock, tabla de destino diferida a Etapa B — pre-aprobada antes de ejecutar.

**Nota no bloqueante:** el run de CI reporta 1 warning de GitHub (Node.js 20 deprecado en `actions/checkout@v4`/`actions/setup-java@v4`, GitHub los fuerza a Node 24). No afecta el build; se resuelve solo con bump de versión de esas actions en algún momento, sin urgencia.

---

## Etapa B — Dominio y extracción

**Objetivo:** record de dominio + clave natural, SQL de extracción (`monitoreofletx-consulta-base-v4.sql`) parametrizado en `resources/sql/`, adaptador de origen + `RowMapper`, migración V2 con la tabla de destino completa (derivada del SELECT de la v4).

**Tareas:**
1. Record `SolicitudFletx` (o nombre equivalente) + `ClaveNatural` sobre `rq.id`.
2. Adaptador `JdbcOrigenPort` con la consulta v4 parametrizada por ventana móvil.
3. `RowMapper` — atención especial a `eventos_detalle` (columna `json`/`jsonb` → mapear a `String` crudo o a un tipo estructurado, decidir y documentar).
4. Fixtures de test del origen con fechas generadas en `@BeforeAll` (nunca en initdb).
5. Migración V2: tabla de destino (columnas = alias del SELECT de la v4, tipos verificados contra Fletx real), índice único de clave natural, índices de consulta, vista `v_monitoreo_fletx_vigente`.

### ✅ Checklist de aprobación — Etapa B — **APROBADA 2026-07-22** (commits b9a2edf…9137e4d, 5 commits + fix post-gate)

- [x] Tipos de columna de la tabla destino confirmados contra Fletx real (no supuestos) — gate ejecutado 2026-07-22, ver `docs/verificacion-tipos-destino-v4.md` (~30 tablas, 6 hallazgos ⚠️). Claude Code confirmó explícitamente 2 de los 6 en el reporte, con evidencia de test (`"2.5 Ton"` para `peso_vacio`, `2` para `standby`).
- [x] Tests de mapeo verdes: 18/18 (2 smoke + 16 de mapeo), corrida dos veces sin reiniciar el contenedor.
- [x] Migración V2 aplicada limpio; confirmada con `\d solicitudes` contra `test_monitoreo_fletx`.
- [x] `EXPLAIN` del SELECT revisado contra Fletx real (Claude Desktop) — **🔴 hallazgo real, corregido antes de aprobar**: el CTE `plantas` (agregado en la v4, [A8]) no tenía pushdown de ventana — agregaba las 1,7M filas completas de `booking_addresses` en cada ejecución, mismo patrón que el bug [B5] ya corregido en `events`. Costo medido: 333.125 → 32.197 (10x) tras agregar `JOIN req_window`. Corregido en `monitoreofletx-consulta-base-v4.sql` (etiquetado `[B11]`) y en `backend/src/main/resources/sql/consulta-base.sql`. Nota de proceso: este bug no se detectó en el gate de viabilidad original de `plantas` (turno anterior) porque esa validación probó cardinalidad/corrección de datos pero no revisó el plan de ejecución en el contexto del JOIN completo — exactamente el tipo de regresión que este checkpoint de EXPLAIN existe para atrapar.
- [x] CI verde: [run 29956376907](https://github.com/feml1976/monitoreofletx/actions/runs/29956376907), 1m24s.

**Pendiente antes de Etapa C:** Claude Code debe correr `mvnw verify` de nuevo tras el fix del CTE `plantas` (el fix no cambia columnas ni semántica, solo el plan de ejecución — no debería romper tests, pero hay que confirmarlo, nunca asumir).

**Prompt listo:** `docs/prompts/etapa-b-dominio-extraccion.md` (ejecutado).

---

## Etapa C — Proyección completa

**Objetivo:** ciclo de sincronización completo — `row_hash`, upsert idempotente, soft-delete, scheduler.

**Tareas:**
1. `computeHash` (MD5, sentinel NUL, fechas ISO-8601) sobre todos los campos de negocio — **nunca en SQL**.
2. Upsert `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE row_hash IS DISTINCT FROM EXCLUDED.row_hash OR deleted_at IS NOT NULL`.
3. `marcarObsoletos` con anti-join vía `unnest`.
4. Servicio orquestador con métricas (counter de ciclos con tag `outcome`) y log estructurado con `run_id`.
5. Scheduler + ShedLock (`lock-at-most-for` < intervalo del cron).

### ✅ Checklist de aprobación — Etapa C — **APROBADA 2026-07-22** (commits 80993c6…d3c8fdc, 5 commits)

- [x] Suite de integración completa: **66/66 tests verdes**, contenedor de test reiniciado en limpio antes de la corrida final. 0 warnings de compilación.
- [x] Métrica de éxito (`outcome`) verificada — **leí el código fuente, no solo el reporte**: en `SincronizarSolicitudesService.sincronizar()`, los tres counters (`insertadosCounter`/`actualizadosCounter`/`omitidosCounter`) se incrementan únicamente después de que `upsert()` y `marcarObsoletos()` retornan sin excepción; el `catch` solo loguea y relanza. Confirmado también con 2 tests explícitos (`fallaEnUpsert_...`, `fallaEnMarcarObsoletos_...`).
- [x] `computeHash` — leí `JdbcProyeccionAdapter.java` completo: cubre los 119 campos de negocio en orden canónico fijo, `eventos_detalle` incluido como texto crudo, los 6 hallazgos ⚠️ del documento de tipos correctamente tratados (`longField` para documentos/referencephones, `intField` para `standby`, `strField` para `peso_vacio`).
- [x] Sentinel de nulos (`NULL_SENTINEL = " "`): **verifiqué contra el código real de Controlt** (`../torre_control/.../JdbcTorreControlProyeccionAdapter.java` línea 30) — es el mismo patrón exacto ya probado en producción, no una desviación ni un bug (el comentario de Claude Code decía "byte NUL" pero el valor real, en ambos proyectos, es un espacio; terminología imprecisa, comportamiento correcto y consistente).
- [x] Upsert con `ON CONFLICT` + resurrección: `WHERE row_hash IS DISTINCT FROM EXCLUDED.row_hash OR deleted_at IS NOT NULL`, `deleted_at = NULL` explícito en el `DO UPDATE` — resurrección correcta incluso si el hash no cambió.
- [x] `marcarObsoletos` con `unnest(bigint[])`, acotado por `fecha_solicitud` dentro de la ventana — no toca filas fuera de ella.
- [x] Salvaguarda no solicitada pero acertada: si el origen devuelve una lista vacía, el servicio **omite** `marcarObsoletos` (seria mostrarlo como progreso real si un fallo silencioso del origen vaciara la ventana) — buen criterio de Claude Code, se deja documentada aquí para no perderla de vista.
- [x] CI verde: [run 29963641304](https://github.com/feml1976/monitoreofletx/actions/runs/29963641304), 1m40s.

**Desviaciones aceptadas:** el cast `::jsonb` explícito en el INSERT (necesario, el driver JDBC no lo hace implícito) y el test end-to-end adicional (cierra el hueco entre tests por componente y el ciclo real) — ambas mejoras razonables, no cambian el contrato.

---

## Etapa D — Validación contra origen real y despliegue

**Objetivo:** correr contra Fletx real y desplegar en `Linux-XPS`.

**Tareas:**
1. Job contra Fletx real, destino `datarmart01`/`monitoreo_fletx` (proyección re-generable — riesgo aceptado, igual que Controlt).
2. Conteos cruzados contra origen (0 duplicados de clave natural).
3. TLS verificado en ambas conexiones (`sslmode=require`).
4. Deploy a `Linux-XPS` vía GHCR (deploy key read-only ya creada + PAT `read:packages` reutilizado de Controlt).
5. 24–48 h de observación con checkpoints (mismo protocolo que Controlt).

### Checklist de aprobación — Etapa D

- [ ] 0 duplicados de clave natural; conteos cuadran contra origen.
- [ ] TLS confirmado en ambas conexiones; usuario de origen solo-SELECT verificado.
- [ ] Checkpoints +2h/+24h/+48h sin anomalías no explicadas.

**Runbook listo:** `docs/prompts/etapa-d-despliegue-linux-xps.md`.

**✅ Pre-requisito bloqueante resuelto (verificado 2026-07-22 vía conector de solo lectura):** el rol de origen ya en uso por Controlt tiene `SELECT` (y únicamente `SELECT` — sin `INSERT`/`UPDATE`/`DELETE`) sobre las 7 tablas nuevas del dataset de MonitoreoFletx: `liquidations`, `comply_destinations`, `booking_addresses`, `addresses`, `consecutive_ministries`, `businessproducts`, `productcodes`. Confirmado además que no es superusuario (`rolsuper=false`, `rolcreatedb=false`, `rolcreaterole=false`). **No se requiere ningún GRANT nuevo antes de desplegar** — mismo rol, sin cambios.

---

## Etapa E — Backfill + observabilidad + cierre

**Objetivo:** completar histórico, alertas probadas con fuego real, cierre documental.

**Tareas:**
1. Backfill según ventana histórica que se defina con el solicitante (§2, pendiente).
2. Nuevo target Prometheus + dashboard Grafana (se reutiliza el stack de Controlt — un target y un dashboard más) + 3 alertas al canal Teams existente, **probadas con fuego real**.
3. Runbook (adaptado del de Controlt).
4. `docs/registro-despliegue-monitoreofletx.md` completo.
5. ADR del proyecto (segunda instancia del patrón como satélite independiente).
6. Monitoreo diario de Cowork extendido al nuevo schema.

### Checklist de aprobación — Etapa E

- [ ] Alertas probadas con fuego real (stop del contenedor → tarjeta en Teams → recuperación).
- [ ] Dashboard con datos reales.
- [ ] Runbook publicado; registro de despliegue completo; ADR registrado.
- [ ] Pendientes [P1]-[P3] del §2 resueltos o explícitamente aceptados como deuda documentada.

---

*Basado en la receta Controlt v1.3.0 (producción, 2026-07-18). Ver `REQUERIMIENTO.md` para el detalle de stack, arquitectura, puertos y restricciones que gobiernan cada etapa.*
