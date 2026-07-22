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

### Checklist de aprobación — Etapa B

- [ ] Tests de mapeo verdes (todas las columnas del SELECT, incluida `eventos_detalle`).
- [ ] `EXPLAIN` del SELECT revisado contra Fletx real (vía Claude Desktop, solo lectura) — sin regresión de performance.
- [ ] Migración V2 aplica limpio sobre schema `test_monitoreo_fletx` vacío.
- [ ] Tipos de columna de la tabla destino confirmados contra Fletx real (no supuestos).

---

## Etapa C — Proyección completa

**Objetivo:** ciclo de sincronización completo — `row_hash`, upsert idempotente, soft-delete, scheduler.

**Tareas:**
1. `computeHash` (MD5, sentinel NUL, fechas ISO-8601) sobre todos los campos de negocio — **nunca en SQL**.
2. Upsert `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE row_hash IS DISTINCT FROM EXCLUDED.row_hash OR deleted_at IS NOT NULL`.
3. `marcarObsoletos` con anti-join vía `unnest`.
4. Servicio orquestador con métricas (counter de ciclos con tag `outcome`) y log estructurado con `run_id`.
5. Scheduler + ShedLock (`lock-at-most-for` < intervalo del cron).

### Checklist de aprobación — Etapa C

- [ ] Suite de integración completa contra PostgreSQL real: hash sensible a cada campo, insert/update/skip, soft-delete, resurrección, fuera-de-ventana, concurrencia ShedLock, vista vigente.
- [ ] Métrica de éxito (`outcome`) verificada: no se incrementa en fallo.
- [ ] 0 warnings de compilación.

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
