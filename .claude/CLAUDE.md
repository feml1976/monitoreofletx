# CLAUDE.md — MonitoreoFletx

## Propósito
Job backend **headless** que materializa una radiografía de solicitudes (`requests`)
de Fletx en el datamart destino (`datarmart01`, schema `monitoreo_fletx`). Segunda
instancia de la receta probada en producción por **Controlt** (`../torre_control`):
mismo origen (Fletx), mismo patrón de ETL — extracción de solo lectura, upsert
idempotente por `row_hash`, sincronización automática cada ~15 min. **No tiene UI.**

`REQUERIMIENTO.md` (raíz del repo) es la fuente de verdad de arquitectura, puertos,
BD y restricciones. `docs/plan-produccion-monitoreofletx.md` gobierna las etapas.
Ante cualquier duda no cubierta aquí: primero mira cómo lo resolvió Controlt; si
sigue ambiguo, pregunta antes de implementar.

## Stack
- Java 21 LTS, Spring Boot 3.5.x, Maven (wrapper `mvnw`).
- Spring JDBC (sin JPA). Flyway (solo destino). ShedLock. Actuator + Micrometer/Prometheus.
- `spring-boot-starter-web` requerido para el management server en 8098.
  `server.port=-1` deshabilita el puerto principal; el job es headless sin tráfico de usuario.
- PostgreSQL 16 (Docker dev/test). JUnit 5 + AssertJ + Mockito + Spring Boot Test.
- **Sin H2, sin TestContainers, sin frontend.**

## Topología de datos
- **Origen**: Fletx (Aurora PostgreSQL), **SOLO LECTURA, sin DDL**. Usuario de solo
  SELECT — verificar grants antes de codificar, no asumir que ya existen.
- **Destino**: `datarmart01`, schema propio `monitoreo_fletx` (pre-creado por el DBA;
  Flyway NO gestiona `public` — es un datamart compartido).
- Dos DataSource: `originDataSource` (read-only) y `datamartDataSource` (`@Primary`).

## Puertos
| Servicio                      | Puerto |
|--------------------------------|--------|
| Backend (management/actuator) | 8098   |
| PostgreSQL (Docker dev/test)  | 5438   |

Nunca usar puertos por defecto (8080, 5432), ni los de Controlt (8097, 5437), ni los
ya ocupados en `Linux-XPS`: 80, 8080, 2222, 3026, 5426, 8026, 8097, 9097, 3097.

## Cómo levantar el entorno (PowerShell 7+)
```powershell
# 1. Variables de entorno
Copy-Item .env.example .env   # y completar valores (NO commitear .env)

# 2. PostgreSQL de desarrollo
docker compose up -d

# 3. Backend (Flyway migra monitoreo_fletx al arrancar)
cd backend
.\mvnw.cmd spring-boot:run
```

## Cómo correr las pruebas
```powershell
docker compose -f docker-compose.test.yml up -d
cd backend
.\mvnw.cmd test
```
Las pruebas de integración corren contra PostgreSQL real en Docker, usando los schemas
`test_monitoreo_fletx` (destino) y `test_origen_mf` (subconjunto del origen con fixtures).
**PROHIBIDO H2 y TestContainers.**

## Convenciones de código
- Inyección por constructor, campos `final`. Records para DTOs/dominio inmutables.
- Paquetes: `com.fml.monitoreofletx.sync.[capa]`. Arquitectura hexagonal (puertos/adaptadores).
- BD destino: `snake_case`, tablas en plural, columnas en singular, soft delete (`deleted_at`).
- Logging estructurado en boundaries; **nunca** `System.out.println`, **nunca** datos
  sensibles (PII de conductores/propietarios) en logs.
- Consultas SIEMPRE parametrizadas. Cero secretos en código (usar `.env`).
- **Nunca** crear objetos ni escribir en el origen.
- Fail-fast: variables críticas de conexión (HOST/PORT/NAME/USERNAME/PASSWORD de ambas
  BD) sin default en `application.yml`. Excepción única: `SCHEMA`/`SSLMODE`.

## Estructura (capas)
```
sync/
  domain/                      # registro de dominio (record) + ClaveNatural — Etapa B
  application/
    port/in/                   # caso de uso de sincronización
    port/out/                  # OrigenPort (read), ProyeccionPort (upsert)
    service/                   # orquestación
  infrastructure/
    adapter/in/scheduler/      # @Scheduled + ShedLock
    adapter/out/origen/        # JDBC SELECT
    adapter/out/proyeccion/    # JDBC upsert ON CONFLICT
    config/                    # DataSourceConfig, FlywayConfig, ShedLockConfig, SyncProperties
```

## Reglas duras
- Origen read-only: prohibido cualquier DDL/escritura allí.
- Upsert idempotente con `row_hash` (Java, MD5) — nunca `md5()` de SQL (formato de
  serialización incompatible).
- Tests contra PG real (Docker), nunca H2/TestContainers.
- Migraciones aplicadas jamás se modifican; toda evolución = migración nueva.
- Etapa A (scaffold + CI) no incluye dominio/extracción/upsert — eso es Etapa B/C.
  La tabla de destino completa (V2, ~90 columnas de `monitoreofletx-consulta-base-v4.sql`)
  se crea en Etapa B, tras verificación de tipos contra Fletx real (Claude Desktop).
- Si algo es ambiguo o riesgoso (tipos en origen, cardinalidad de joins), preguntar/
  reportar antes de implementar.
