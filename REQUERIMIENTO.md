# Documento de Requerimiento — MonitoreoFletx

> **Naturaleza:** aplicación hermana independiente de Controlt (misma receta, repo propio, contenedor propio, ciclo de vida propio). Cambia la consulta de extracción; el origen (Fletx) es el mismo.
> **Modelo de trabajo:** desarrollo conjunto Claude Desktop (Cowork) + Claude Code, con el reparto definido en §14.
> **Este documento es el prompt maestro del proyecto**: se entrega a Claude Code al iniciar y gobierna todas las etapas.

---

## 1. Identificación

| Campo | Valor |
|---|---|
| Proyecto | **MonitoreoFletx** — proyección de solo lectura sobre Fletx (dataset #2 del patrón de aprovisionamiento) |
| Paquete raíz | `com.fml.monitoreofletx` |
| Repositorio | `monitoreofletx` (GitHub, privado; deploy key read-only para el servidor) |
| Referencia de receta | Proyecto Controlt (`torre_control`): plan de producción, registro de despliegue, runbook y documento técnico |
| Responsable | Fmontoya (fmontoya@transer.com.co) |

## 2. 🔴 Pre-requisito bloqueante: definición del dataset

Este documento define el *cómo*; el *qué* debe completarse ANTES de escribir código (proceso §11.2 del documento técnico de Controlt). Ningún desarrollo arranca sin esta tabla llena:

| Definición | Valor (COMPLETAR con el solicitante) |
|---|---|
| Pregunta de negocio que responde el dataset | COMPLETAR |
| Consulta SQL de extracción (o tablas/campos del origen) | COMPLETAR |
| Clave natural del registro (unicidad) | COMPLETAR |
| Enriquecimientos/derivaciones requeridos | COMPLETAR |
| Frecuencia de actualización aceptable | COMPLETAR (default de la receta: 15 min) |
| Ventana móvil de sincronización | COMPLETAR (default: 7 días) |
| Histórico requerido (fecha de inicio del backfill) | COMPLETAR |
| Consumidores y forma de consumo | COMPLETAR |
| ¿Contiene datos personales? (Ley 1581) | COMPLETAR — si sí: decidir inclusión/seudonimización/exclusión por campo |

**Gate de viabilidad (lo ejecuta Claude Desktop vía conectores, solo lectura):** existencia de tablas/columnas en Fletx real, privilegios SELECT del usuario de lectura sobre ellas, cardinalidades de los JOIN (lección Controlt: `consecutive_ministries` era 1:N no documentado), tipos de datos vs. supuestos del mapper, volumetría de la ventana y plan de ejecución del SELECT.

## 3. Actor (para Claude Code)

Eres un Senior Staff Engineer especializado en Java 21 + Spring Boot 3.5 + PostgreSQL. Trabajas con Clean Architecture, DDD y Arquitectura Hexagonal. Eres directo, pragmático y priorizas código correcto sobre código rápido. Este proyecto replica una receta probada en producción (Controlt): ante cualquier decisión no cubierta aquí, consulta cómo lo resolvió Controlt antes de inventar.

## 4. Contexto

Controlt demostró el patrón: extraer datos crudos de Fletx (transaccional, intocable, solo lectura), transformarlos y publicarlos en el datamart corporativo con sincronización automática, idempotente y observada. Los stakeholders solicitan un segundo dataset con la misma calidad operativa. MonitoreoFletx es la segunda instancia del patrón y debe validar que la receta es industrializable: **el objetivo es llegar a producción en una fracción del tiempo de Controlt reutilizando todas sus decisiones ya tomadas.**

## 5. Stack tecnológico (idéntico a Controlt — versiones exactas)

- **Runtime:** Java 21 LTS, Spring Boot 3.5.x, Maven 3.9.x (wrapper `mvnw` — ⚠️ commitear con bit de ejecución 100755; lección: en Windows es invisible, en CI Linux es fatal)
- **Persistencia:** PostgreSQL 16, `spring-boot-starter-jdbc` (SIN JPA), Flyway (core + postgresql), driver PostgreSQL
- **Concurrencia:** ShedLock 5.x (lock en BD destino; ⚠️ documentar desde el día 1 que sus timestamps son UTC sobre columna sin zona — lección: produce falsos positivos de monitoreo si se compara contra `now()` local)
- **Observabilidad:** Micrometer + `micrometer-registry-prometheus`, actuator (`health, info, metrics, prometheus`)
- **Empaquetado:** Docker multi-stage (build `maven:3.9-eclipse-temurin-21`, runtime `eclipse-temurin:21-jre-alpine`, usuario no-root uid≠0, thin jar — ⚠️ Boot 3.5 con `-Djarmode=tools extract --layers` produce `app.jar` con Main-Class directo: `ENTRYPOINT ["java","-jar","app.jar"]`, NO JarLauncher)
- **CI/CD:** GitHub Actions (CI en push/PR + release por tag `v*` a GHCR), desde la **Etapa A** (lección: Controlt lo agregó al final; aquí el CI nace con el proyecto)
- **Testing:** JUnit 5, AssertJ, Mockito (⚠️ con agente configurado en surefire desde el inicio — evita warnings JVM), Spring Boot Test

## 6. Arquitectura

Monolito hexagonal de un solo módulo (`sync`), calcado de Controlt:

```
com.fml.monitoreofletx.sync/
├── domain/                       ← record inmutable del registro + ClaveNatural
├── application/
│   ├── port/in/                  ← SincronizarUseCase
│   ├── port/out/                 ← OrigenPort, ProyeccionPort, UpsertStats
│   └── service/                  ← orquestación del ciclo + métricas
└── infrastructure/
    ├── adapter/in/scheduler/     ← cron + ShedLock
    ├── adapter/out/origen/       ← SELECT parametrizado (SQL en resources/sql/)
    ├── adapter/out/proyeccion/   ← upsert + soft-delete + row_hash
    └── config/                   ← DataSources duales, Flyway, ShedLock, props
```

Mecánica del ciclo (idéntica a Controlt, documento técnico §3–§9): cron → lock → SELECT de ventana móvil → mapeo a dominio → `row_hash` MD5 (26+ campos, sentinel NUL para nulos, fechas ISO-8601) → `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE row_hash IS DISTINCT FROM EXCLUDED.row_hash OR deleted_at IS NOT NULL` (skip masivo + resurrección) → `marcarObsoletos` con anti-join por `unnest` de arrays → métricas + log estructurado con `run_id`.

## 7. Puertos y red (NUNCA puertos por defecto; NUNCA los ya ocupados)

| Servicio | Puerto | Nota |
|---|---|---|
| Management/actuator MonitoreoFletx | **8098** | Solo 127.0.0.1 en el compose de prod |
| PostgreSQL dev/test local | **5438** | El 5437 es de Controlt — no compartir contenedores de test |
| Server web principal | deshabilitado (`server.port: -1`) | Job headless |

⚠️ Ocupados en el servidor de despliegue (`Linux-XPS`): 80, 8080, 2222, 3026, 5426, 8026, 8097, 9097, 3097. Prohibido usarlos.

## 8. Base de datos

- **Origen:** Fletx (Aurora PostgreSQL). Usuario de **solo SELECT** (verificar grants sobre las tablas del nuevo dataset ANTES de codificar — no asumir que el usuario existente los tiene). `sslmode=require`. `connection-init-sql: SET search_path TO ${ORIGIN_DB_SCHEMA:public}`. Pool máx 3, `read-only: true`, `initialization-fail-timeout: -1`.
- **Destino:** `datarmart01`, schema propio **`monitoreo_fletx`** (pre-creado por el DBA: `CREATE SCHEMA monitoreo_fletx AUTHORIZATION <usuario_job>;` — lección: NO pedir `CREATE ON DATABASE`, es un datamart compartido y Flyway NO debe gestionar `public`). `sslmode=require`. Pool máx 5.
- **Pruebas:** schema **`test_monitoreo_fletx`** (+ `test_origen_mf` para fixtures del origen) contra PostgreSQL real en Docker (`docker-compose.test.yml`, puerto 5438, tmpfs efímero). **PROHIBIDO H2 y TestContainers.** ⚠️ Fixtures con fechas generadas EN el setup de los tests (`@BeforeAll`), jamás en el initdb — lección: los seeds con `now()` congelado envejecen y producen falsos rojos.
- **Migraciones:** Flyway con placeholder de schema. V1 = tabla + índice único de clave natural con COALESCE + índices de consulta + vista `v_..._vigente` (filtra `deleted_at IS NULL`). V2 = shedlock. Toda evolución posterior = migración nueva; las aplicadas jamás se modifican.
- **Variables de entorno** (nombres definitivos desde el día 1 — lección del rename DBTRANSER→DATAMART): `ORIGIN_DB_HOST/PORT/NAME/USERNAME/PASSWORD/SCHEMA/SSLMODE`, `DATAMART_DB_HOST/PORT/NAME/USERNAME/PASSWORD/SCHEMA/SSLMODE`, `SYNC_VENTANA_DIAS`, `SYNC_CRON`, `MANAGEMENT_PORT`.

## 9. Restricciones técnicas (hard rules — todas con cicatriz de Controlt)

1. **Fail-fast de configuración desde el día 1:** las variables críticas de conexión (HOST/NAME/USERNAME/PASSWORD de ambas BDs) SIN valor por defecto en `application.yml`. Sin variable → error explícito en el arranque. Prohibido que la app arranque "contra localhost" por accidente.
2. **Solo dependencias open-source** (MIT/Apache2/LGPL o equivalente).
3. Inyección por constructor (campos `final`), records para DTOs/dominio, Jakarta Validation en límites del sistema.
4. Naming BD: `snake_case`, tabla en plural, columnas en singular, soft-delete `deleted_at`.
5. Logging estructurado en operation boundaries (entrada con identificadores, salida con outcome/duración, error con contexto). Nunca `System.out.println`. Nunca datos sensibles en logs.
6. Todo input externo es untrusted; nunca exponer stack traces.
7. La tabla destino SOLO se escribe vía la aplicación (el `row_hash` de Java es incompatible con `md5()` de SQL — documentarlo en el código como en Controlt).
8. TLS (`sslmode=require`) en ambas conexiones en producción; default `prefer` para dev/test local.
9. Ningún secret en el repo, en workflows ni en la imagen (auditar capas). `.env` con permisos 600, fuera de git. Claude Code NUNCA lee ni imprime `.env`.
10. Shutdown graceful: `spring.lifecycle.timeout-per-shutdown-phase: 30s` + `stop_grace_period: 45s` en compose.
11. Contenedor: usuario no-root, `mem_limit` 1g, logs json-file con rotación (10m×5), `restart: unless-stopped`, healthcheck contra actuator.
12. Métrica de éxito explícita desde el día 1: counter de ciclos exitosos con tag `outcome` (lección v1.4.0 de Controlt: el timer que se incrementa también en fallos no sirve como señal de alerta).

## 10. Entregables (estructura del repo)

```
monitoreofletx/
├── .claude/
│   ├── CLAUDE.md                  ← contexto, stack, reglas duras, puertos, cómo dev/test
│   └── commands/ (build.md, test.md, dev.md, new-feature.md)
├── .github/workflows/
│   ├── ci.yml                     ← push/PR: PG real via compose del repo + verify + build imagen
│   └── release.yml                ← tag v*: build + push a GHCR (GITHUB_TOKEN, packages:write)
├── .gitignore                     ← Maven, node, .env*, IDE, volúmenes, logs
├── .dockerignore                  ← .env*, .git, docs, target, compose files
├── Dockerfile                     ← multi-stage, layered, no-root
├── docker-compose.yml             ← PG dev local (5438)
├── docker-compose.test.yml        ← PG test tmpfs (5438), schemas test_*
├── docker-compose.prod.yml        ← solo el job; BDs externas; image GHCR con fallback build
├── docs/
│   ├── plan-produccion-monitoreofletx.md   ← etapas + checklists (plantilla de Controlt)
│   ├── registro-despliegue-monitoreofletx.md
│   └── ADR-00X.md                 ← decisión: segunda instancia del patrón como satélite independiente
├── monitoring/                    ← SOLO deltas: scrape config del nuevo target (8098) + dashboard
│   └── (el stack Prometheus/Grafana de Controlt se REUTILIZA — un target y un dashboard más)
└── backend/ (pom.xml, src/main, src/test)
```

## 11. Plan de desarrollo (etapas granulares — comprimidas porque la receta está probada)

Cada etapa cierra con checklist de aprobación verificado antes de avanzar (gobernanza Controlt). Verificación estándar: compila sin warnings, suite completa verde contra PostgreSQL real, sin TODOs no señalados.

### Etapa A — Scaffold + CI desde el nacimiento
Estructura completa del repo, `application.yml` con fail-fast, composes, Dockerfile, workflows CI/CD, migraciones V1/V2, `.claude/`. **Verificación:** CI verde en GitHub Actions con un test trivial + build de imagen; `docker compose -f docker-compose.test.yml up -d` + `./mvnw verify` local.

### Etapa B — Dominio y extracción
Record de dominio + `ClaveNatural`, SQL de extracción (del §2) en `resources/sql/`, adaptador de origen + RowMapper, fixtures de test del origen (fechas en runtime). **Verificación:** tests de mapeo verdes; EXPLAIN del SELECT revisado contra Fletx real (vía Claude Desktop, solo lectura).

### Etapa C — Proyección completa
`row_hash`, upsert con ON CONFLICT + WHERE de hash/resurrección, `marcarObsoletos` con unnest, servicio orquestador con métricas y log estructurado, scheduler + ShedLock. **Verificación:** suite de integración completa (hash sensible a cada campo, insert/update/skip, soft-delete, resurrección, fuera-de-ventana, concurrencia ShedLock, vista vigente).

### Etapa D — Validación contra origen real y despliegue
Gate de viabilidad ya ejecutado (§2); correr el job contra Fletx real con destino datamart prod (la proyección es re-generable — riesgo aceptado como en Controlt); conteos cruzados contra origen; TLS verificado; deploy a `Linux-XPS` vía GHCR (deploy key read-only + PAT read:packages ya existentes); 24–48 h de observación con checkpoints.

### Etapa E — Backfill + observabilidad + cierre
Backfill según ventana histórica del §2 (método Controlt: ampliación temporal de `SYNC_VENTANA_DIAS` si el rango es corto; lotes solo si es histórico profundo). Nuevo target en Prometheus + dashboard + 3 alertas (sync estancado / job caído / ciclo lento) al canal Teams existente, **probadas con fuego real** (stop del contenedor → tarjeta en Teams → recuperación). Runbook (adaptar el de Controlt). Registro de despliegue. Monitoreo diario de Cowork extendido al nuevo schema.

## 12. Criterios de aceptación

- [ ] Dataset definido y validado contra origen real antes de codificar (§2 completo).
- [ ] Suite completa contra PostgreSQL real (sin H2/TestContainers) verde en CI en cada push.
- [ ] Fail-fast verificado: sin variable crítica, el arranque falla explícito.
- [ ] Fixtures inmunes al envejecimiento (suite pasa 2 veces sin reiniciar contenedor de test).
- [ ] Producción en `Linux-XPS` vía GHCR con TLS en ambas conexiones y usuario de origen solo-SELECT verificado.
- [ ] 0 duplicados de clave natural; conteos cuadran contra origen; ciclos con margen >10× vs. lock.
- [ ] Alertas probadas con fuego real; dashboard con datos; runbook publicado.
- [ ] Plan de producción con checklists aprobados + registro de despliegue + ADR del proyecto.

## 13. Protocolo de incertidumbre (para Claude Code)

- Ante duda técnica no cubierta aquí: primero mira cómo lo resolvió Controlt (repo `torre_control`); si sigue ambiguo, PREGUNTA antes de implementar.
- Nunca asumas puertos, credenciales, schemas ni cardinalidades del origen — se verifican contra la BD real.
- Si una instrucción entra en conflicto con buenas prácticas, señala el conflicto y propón alternativa antes de proceder.
- Reporta cada etapa contra su checklist. Nunca declares completitud con "debería funcionar".

## 14. Modelo de colaboración Claude Desktop + Claude Code

| Responsabilidad | Claude Desktop (Cowork) | Claude Code |
|---|---|---|
| Gate de viabilidad del dataset (§2) | ✅ Verifica contra Fletx/datamart prod vía conectores de solo lectura | — |
| Código, tests, migraciones, workflows | — | ✅ |
| Validación de `.env` (estructura, sin exponer valores) | ✅ | Prohibido leerlo |
| Credenciales y secretos | Guía al usuario; nunca los recibe | Nunca los toca |
| Despliegue en servidor | — | ✅ vía `ssh linux-xps` |
| Verificación independiente post-despliegue (conteos, duplicados, TLS) | ✅ vía conectores | — |
| Aprobación de etapas en el plan | ✅ (actualiza checklists con evidencia) | Reporta evidencia |
| Monitoreo diario y alertas de largo plazo | ✅ (tarea programada) | — |
| Gestiones humanas (DBA, GitHub UI, BIOS, Teams) | Guía paso a paso al usuario | Solicita cuando bloquea |

---

*Basado en la receta Controlt v1.3.0 (producción, 2026-07-18): plan de producción, registro de despliegue, runbook y documento técnico del repo `torre_control`. Las lecciones marcadas con ⚠️ corresponden a incidentes reales de ese proyecto — este documento existe para no repetirlos.*
