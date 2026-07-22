# Prompt Claude Code — Etapa A: Scaffold + CI desde el nacimiento

> **Uso:** pegar este prompt completo en Claude Code, ejecutándolo desde la raíz del repo `monitoreofletx`.
> **Referencia:** `REQUERIMIENTO.md` (raíz de este repo) — documento maestro, §5 a §12. `docs/plan-produccion-monitoreofletx.md` §Etapa A.

---

## Contexto

Eres un Senior Staff Engineer construyendo **MonitoreoFletx** (`com.fml:monitoreofletx`), un ETL headless en Java 21 + Spring Boot 3.5 que sincroniza un dataset de solicitudes (`requests`) desde Fletx (origen, solo lectura) hacia `datarmart01`/schema `monitoreo_fletx` (destino). Es la **segunda instancia** de una receta ya probada en producción: el proyecto hermano **Controlt** vive en `../torre_control` (ruta relativa, mismo directorio padre en esta máquina) y **ya resolvió** cada decisión de arquitectura, configuración, contenerización y CI/CD que este repo necesita.

**Regla de oro de esta etapa: no reinventes. Copia y adapta.** Antes de escribir cualquier archivo de infraestructura (Dockerfile, workflows, compose, `.claude/`), léelo primero en `../torre_control` y adáptalo — cambiando solo lo que difiere (nombres de paquete, puertos, variables de entorno, nombres de servicio). Si algo en Controlt te parece mejorable, señálalo como sugerencia al final del reporte, pero **no lo cambies aquí sin preguntar** — el objetivo de esta etapa es paridad, no innovación.

Esta etapa **NO** incluye lógica de negocio (dominio, extracción, upsert) — eso es Etapa B/C. Al final de esta etapa el proyecto debe compilar, tener un test trivial verde en CI, y producir una imagen Docker que arranca (aunque no haga nada útil todavía).

Lee `REQUERIMIENTO.md` completo antes de empezar.

## Reglas duras (no negociables — de `REQUERIMIENTO.md` §9)

1. **Fail-fast desde el día 1:** ninguna variable crítica de conexión (`ORIGIN_DB_*`, `DATAMART_DB_*` con excepción de `SCHEMA`/`SSLMODE` que sí tienen default) lleva valor por defecto en `application.yml`. Sin la variable, el arranque falla explícito.
2. Solo dependencias open-source (MIT/Apache2/LGPL o equivalente) — las mismas que usa Controlt.
3. Inyección por constructor (campos `final`), records para DTOs/dominio, Jakarta Validation en los límites del sistema.
4. Naming BD: `snake_case`, tablas en plural, columnas en singular, soft-delete `deleted_at`.
5. Logging estructurado en operation boundaries. Nunca `System.out.println`. Nunca datos sensibles en logs.
6. TLS (`sslmode=require`) en ambas conexiones en producción; default `prefer` en dev/test local.
7. Ningún secret en el repo, workflows ni imagen. `.env` con permisos 600, fuera de git. **Nunca leas ni imprimas `.env`** si llegas a encontrarlo — no debería existir en este repo todavía.
8. Shutdown graceful: `spring.lifecycle.timeout-per-shutdown-phase: 30s`.
9. Puertos — **nunca los de Controlt ni los ya ocupados en `Linux-XPS`** (80, 8080, 2222, 3026, 5426, 8026, 8097, 9097, 3097): usa **8098** (management) y **5438** (PostgreSQL dev/test). `server.port: -1` (job headless).
10. **Pruebas contra PostgreSQL real únicamente. PROHIBIDO H2 y TestContainers.** Schema `test_monitoreo_fletx` (+ `test_origen_mf` si se necesitan fixtures del origen).
11. `mvnw` se commitea con bit de ejecución **100755** (Controlt tuvo un incidente real por esto: invisible en Windows, fatal en el runner Linux de CI).
12. CI/CD **desde este mismo commit inicial** — a diferencia de Controlt, que lo agregó al final del proyecto.

## Deviación explícita respecto al REQUERIMIENTO.md — léela antes de tocar Flyway

El §8 del `REQUERIMIENTO.md` describe "V1 = tabla de destino + índices + vista vigente; V2 = shedlock". **En esta etapa invierte el orden:** crea únicamente **V1 = ShedLock** (mirror exacto de la migración de ShedLock de Controlt). La tabla de destino completa (con ~90 columnas derivadas de `monitoreofletx-consulta-base-v4.sql`) requiere que Claude Desktop verifique tipo por tipo contra Fletx real primero — hacerlo ahora, a ciegas, arriesga una migración V2 incompleta que después haya que **modificar** (regla dura: las migraciones aplicadas jamás se tocan). Esa tabla se crea en la Etapa B. Documenta esta decisión en el mensaje del commit de la migración.

## Pre-requisito

Verifica que estás en la raíz de `monitoreofletx`, rama `main`, con `REQUERIMIENTO.md` y `monitoreofletx-consulta-base-v4.sql` ya presentes (fueron commiteados antes de esta etapa). Confirma acceso de lectura a `../torre_control`.

---

## Pasos

### Paso 1 — `.gitignore` / `.dockerignore`

Copia y adapta de `../torre_control` (Maven, `.env*`, IDE, volúmenes, logs, `target/`). `.dockerignore` excluye `.env*`, `.git`, `docs/`, `target/`, archivos de compose.

- Commit: `chore: gitignore y dockerignore iniciales`

### Paso 2 — `pom.xml` y esqueleto de paquetes

`backend/pom.xml`: Java 21, Spring Boot 3.5.x parent, `spring-boot-starter-jdbc` (**sin JPA**), `flyway-core` + `flyway-database-postgresql`, driver PostgreSQL, `shedlock-spring` + `shedlock-provider-jdbc-template`, `micrometer-registry-prometheus`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, JUnit 5 + AssertJ + Mockito (con el agente de Mockito configurado en surefire desde el inicio — Controlt lo agregó tarde y tuvo warnings de JVM evitables).

Paquete raíz `com.fml.monitoreofletx.sync` con la estructura del §6 del `REQUERIMIENTO.md` (`domain/`, `application/port/{in,out}/`, `application/service/`, `infrastructure/adapter/in/scheduler/`, `infrastructure/adapter/out/{origen,proyeccion}/`, `infrastructure/config/`). Crea una clase `Application` mínima (`@SpringBootApplication`) que arranque sin lógica de negocio.

- Commit: `feat: scaffold de pom.xml y estructura de paquetes hexagonal`

**Verificación:** `./mvnw -f backend/pom.xml compile` sin errores.

### Paso 3 — `application.yml` (fail-fast) + `application-test.yml`

Variables (nombres definitivos, ver §8 del `REQUERIMIENTO.md`): `ORIGIN_DB_HOST/PORT/NAME/USERNAME/PASSWORD` sin default; `ORIGIN_DB_SCHEMA:public`, `ORIGIN_DB_SSLMODE:prefer` con default. Mismo patrón para `DATAMART_DB_*`. `SYNC_VENTANA_DIAS:7`, `SYNC_CRON:0 0/15 * * * *`, `MANAGEMENT_PORT:8098`. `server.port: -1`. `management.endpoints.web.exposure.include: health,info,metrics,prometheus`. Pool origen máx 3 + `read-only: true` + `initialization-fail-timeout: -1` + `connection-init-sql: SET search_path TO ${ORIGIN_DB_SCHEMA:public}`; pool destino máx 5. `spring.lifecycle.timeout-per-shutdown-phase: 30s`.

`application-test.yml` (perfil `test`): apunta a `localhost:5438`, schemas `test_monitoreo_fletx` / `test_origen_mf`, `sslmode=prefer`.

- Commit: `feat(config): application.yml con fail-fast y perfil de test`

**Verificación:** arrancar sin las variables críticas definidas → falla explícito citando la variable faltante (no cae a `localhost` en silencio).

### Paso 4 — Migración Flyway V1 (ShedLock — ver deviación explícita arriba)

`backend/src/main/resources/db/migration/V1__crea_shedlock.sql`: mirror exacto de la tabla ShedLock de Controlt, con placeholder de schema (`${flyway:defaultSchema}` o el mecanismo que use Controlt).

- Commit: `feat(db): V1 crea tabla shedlock (tabla de destino diferida a Etapa B — ver docs/prompts/etapa-a-scaffold-ci.md)`

**Verificación:** `flyway:migrate` (o el test de arranque) aplica limpio sobre `test_monitoreo_fletx` vacío.

### Paso 5 — Composes

`docker-compose.yml` (dev): PostgreSQL en **5438** (nunca 5437, que es de Controlt).
`docker-compose.test.yml`: PostgreSQL de test, tmpfs, puerto 5438, init de los schemas `test_monitoreo_fletx` y `test_origen_mf`.
`docker-compose.prod.yml`: solo el servicio del job (BDs externas), `env_file: .env`, `restart: unless-stopped`, `mem_limit: 1g`, `stop_grace_period: 45s`, logging `json-file` con rotación 10m×5, healthcheck contra `/actuator/health` en 8098.

- Commit: `feat(docker): compose de dev, test y producción`

### Paso 6 — `Dockerfile`

Multi-stage: build `maven:3.9-eclipse-temurin-21` (con caché de `~/.m2` si Controlt lo hace así), runtime `eclipse-temurin:21-jre-alpine`, usuario no-root, `EXPOSE 8098`, `HEALTHCHECK` contra el actuator, `ENTRYPOINT ["java","-jar","app.jar"]` (**no** `JarLauncher` — lección de Controlt en Boot 3.5 con `jarmode=tools`).

- Commit: `feat(docker): Dockerfile multi-stage no-root`

**Verificación:** `docker build` exitoso; `docker run` (sin BDs configuradas) falla explícito por fail-fast, no crashea silenciosamente.

### Paso 7 — GitHub Actions

`.github/workflows/ci.yml`: en push/PR, levanta PostgreSQL real vía `docker-compose.test.yml`, corre `./mvnw verify`, construye la imagen (sin publicarla).
`.github/workflows/release.yml`: en tag `v*`, build + push a GHCR con `GITHUB_TOKEN` (scope `packages:write`).

Copia la estructura exacta de `../torre_control/.github/workflows/` y adapta nombres de imagen/servicio.

- Commit: `feat(ci): workflows de CI y release a GHCR`

**Verificación:** después del push final (Paso 11), el workflow de CI debe quedar verde en GitHub Actions.

### Paso 8 — `.claude/`

`.claude/CLAUDE.md`: contexto del proyecto, stack, reglas duras (resumen de §9), puertos, cómo levantar dev/test, referencia a `REQUERIMIENTO.md` como fuente de verdad.
`.claude/commands/`: `build.md`, `test.md`, `dev.md`, `new-feature.md` — copia y adapta los de Controlt.

- Commit: `docs: .claude/CLAUDE.md y commands`

### Paso 9 — `.env.example` y `README.md`

`.env.example`: todas las variables del Paso 3, comentadas donde tengan default, **sin ningún valor real**.
`README.md`: propósito del proyecto, cómo levantar dev/test, tabla de variables de entorno, link a `REQUERIMIENTO.md` y a `docs/plan-produccion-monitoreofletx.md`.

- Commit: `docs: env.example y README`

### Paso 10 — Test trivial y verificación local

Un test de contexto Spring (`@SpringBootTest`) que solo verifica que el contexto levanta contra `test_monitoreo_fletx` (con las BDs de test en `docker-compose.test.yml` arriba). No hay lógica de negocio que testear todavía.

- Commit: `test: smoke test de contexto Spring`

**Verificación:**
```
docker compose -f docker-compose.test.yml up -d
./mvnw -f backend/pom.xml clean verify
```
Debe quedar verde, cero warnings de compilación.

### Paso 11 — Push y verificación de CI

1. `git log --oneline` → confirma los commits de esta etapa.
2. `git push`.
3. Verifica en GitHub Actions que `ci.yml` corre y queda verde.

Reporta al final: resultado de `mvnw verify`, lista de commits, estado del workflow de CI, y cualquier desviación respecto a estos pasos con su justificación (además de la deviación de Flyway ya documentada).

---

## Fuera de alcance de este prompt

- Dominio, extracción, `row_hash`, upsert, soft-delete, scheduler — Etapa B/C.
- Tabla de destino completa y su migración V2 — Etapa B (requiere verificación de tipos contra Fletx real por Claude Desktop).
- Despliegue en `Linux-XPS` — Etapa D.
- Monitoring/dashboard — Etapa E (se reutiliza el stack de Controlt).

## Checklist de aprobación de la Etapa A (validar contra `docs/plan-produccion-monitoreofletx.md`)

- [ ] CI verde en GitHub Actions con el smoke test + build de imagen.
- [ ] `docker compose -f docker-compose.test.yml up -d` + `./mvnw verify` en verde localmente.
- [ ] Fail-fast verificado con variables críticas ausentes.
- [ ] `mvnw` commiteado con bit de ejecución 100755.
- [ ] `.env.example` sin valores reales; sin `.env` trackeado.
- [ ] Puertos 8098/5438 usados consistentemente; ningún puerto de Controlt ni de la lista de ocupados en `Linux-XPS`.
- [ ] Deviación de Flyway (V1=shedlock, tabla de destino diferida) documentada en el commit y en el reporte final.
- [ ] Push a GitHub realizado.
