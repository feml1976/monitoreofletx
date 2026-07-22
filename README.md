# MonitoreoFletx

Job ETL headless que materializa una radiografía de solicitudes (`requests`) de
Fletx en el datamart destino (`datarmart01`, schema `monitoreo_fletx`).

Segunda instancia de la receta probada en producción por **Controlt**
(`torre_control`): mismo origen (Fletx, solo lectura), mismo patrón de proyección
read-model (CQRS) poblada cada ~15 minutos mediante upsert idempotente con
detección de cambios por `row_hash`.

> **Estado:** Etapa A (scaffold + CI) — sin lógica de negocio todavía. Ver
> `REQUERIMIENTO.md` (documento maestro) y `docs/plan-produccion-monitoreofletx.md`
> (plan de etapas y checklists).

---

## Arquitectura en una línea

```
Origen Fletx (SOLO LECTURA)
    └─ SELECT ventana móvil (monitoreofletx-consulta-base-v4.sql — Etapa B)
         └─ Servicio de sincronización (Etapa C)
              └─ ON CONFLICT upsert + row_hash
                   └─ tabla de destino (datamart, schema monitoreo_fletx — Etapa B)
```

El backend no expone endpoints de negocio. El único puerto activo es el
management server de Actuator en **8098**.

---

## Stack

| Componente | Versión |
|---|---|
| Java | 21 LTS |
| Spring Boot | 3.5.0 |
| Spring JDBC | (sin JPA) |
| Flyway | 11.x |
| ShedLock | 5.14.0 |
| Micrometer / Prometheus | Spring Boot BOM |
| PostgreSQL | 16 (Docker) |
| Maven wrapper | 3.9.x |

---

## Estructura del proyecto

```
monitoreofletx/
├── backend/                        # Módulo Maven principal
│   ├── src/main/java/com/fml/monitoreofletx/sync/
│   │   ├── domain/                 # registro de dominio (record) — Etapa B
│   │   ├── application/
│   │   │   ├── port/in/            # caso de uso de sincronización — Etapa B/C
│   │   │   ├── port/out/           # OrigenPort, ProyeccionPort — Etapa B/C
│   │   │   └── service/            # orquestación + métricas — Etapa C
│   │   └── infrastructure/
│   │       ├── adapter/in/scheduler/   # @Scheduled + ShedLock — Etapa C
│   │       ├── adapter/out/origen/     # JDBC SELECT (SOLO LECTURA) — Etapa B
│   │       ├── adapter/out/proyeccion/ # JDBC upsert ON CONFLICT — Etapa C
│   │       └── config/                 # DataSourceConfig, FlywayConfig, ShedLockConfig, SyncProperties
│   └── src/main/resources/
│       ├── sql/                    # Consulta parametrizada del origen — Etapa B
│       └── db/migration/           # V1__crea_shedlock.sql (tabla de destino: Etapa B)
├── docker/init-test/               # Schemas y fixtures para tests
├── docker-compose.yml              # PostgreSQL dev (puerto 5438, volumen persistente)
├── docker-compose.test.yml         # PostgreSQL test (puerto 5438, tmpfs efímero)
├── docker-compose.prod.yml         # Solo el job; BDs externas
├── .claude/CLAUDE.md               # Guía de arquitectura y reglas del proyecto
├── REQUERIMIENTO.md                # Documento maestro (gobierna todas las etapas)
├── docs/plan-produccion-monitoreofletx.md  # Plan de etapas y checklists
└── .env.example                    # Plantilla de variables de entorno
```

---

## Puertos

| Servicio | Puerto |
|---|---|
| Actuator / management | **8098** |
| PostgreSQL Docker (dev y test) | **5438** |

> No se usan los puertos por defecto (8080, 5432), ni los de Controlt (8097, 5437),
> ni los ya ocupados en el servidor de despliegue `Linux-XPS` (80, 8080, 2222, 3026,
> 5426, 8026, 8097, 9097, 3097).

---

## Variables de entorno

Copia `.env.example` a `.env` y completa los valores reales.
**Nunca commitear `.env`.**

| Variable | Default | Notas |
|---|---|---|
| `DATAMART_DB_HOST` | — (obligatoria) | Sin default: fail-fast si falta |
| `DATAMART_DB_PORT` | — (obligatoria) | Sin default: fail-fast si falta |
| `DATAMART_DB_NAME` | — (obligatoria) | Sin default: fail-fast si falta |
| `DATAMART_DB_USERNAME` | — (obligatoria) | Sin default: fail-fast si falta |
| `DATAMART_DB_PASSWORD` | — (obligatoria) | Sin default: fail-fast si falta |
| `DATAMART_DB_SCHEMA` | `monitoreo_fletx` | Schema de destino, pre-creado por el DBA |
| `DATAMART_DB_SSLMODE` | `prefer` | Usar `require` en producción |
| `ORIGIN_DB_HOST` | — (obligatoria) | Sin default: fail-fast si falta |
| `ORIGIN_DB_PORT` | — (obligatoria) | Sin default: fail-fast si falta |
| `ORIGIN_DB_NAME` | — (obligatoria) | Sin default: fail-fast si falta |
| `ORIGIN_DB_USERNAME` | — (obligatoria) | Sin default: fail-fast si falta |
| `ORIGIN_DB_PASSWORD` | — (obligatoria) | Sin default: fail-fast si falta |
| `ORIGIN_DB_SCHEMA` | `public` | Schema del origen en Fletx |
| `ORIGIN_DB_SSLMODE` | `prefer` | Usar `require` en producción |
| `SYNC_VENTANA_DIAS` | `7` | Días hacia atrás desde `NOW()` |
| `SYNC_CRON` | `0 0/15 * * * *` | Formato Spring cron de 6 campos |
| `MANAGEMENT_PORT` | `8098` | Puerto del actuator/management |

---

## Levantar entorno de desarrollo

```powershell
# 1. Variables de entorno
Copy-Item .env.example .env   # completar valores reales

# 2. PostgreSQL de desarrollo
docker compose up -d

# 3. Cargar .env en la sesión de PowerShell
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim())
    }
}

# 4. Arrancar el backend (Flyway migra al primer arranque)
cd backend
.\mvnw.cmd spring-boot:run
```

Verificación rápida: `Invoke-RestMethod http://localhost:8098/actuator/health` → `status: UP`.

---

## Ejecutar tests

```powershell
# 1. PostgreSQL de test (tmpfs efímero, arranca limpio cada vez)
docker compose -f docker-compose.test.yml up -d

# 2. Suite completa
cd backend
.\mvnw.cmd clean verify
```

Las pruebas corren contra PostgreSQL real en Docker (`test_monitoreo_fletx` +
`test_origen_mf`). **PROHIBIDO H2 y TestContainers.**

---

## Build limpio

```powershell
cd backend
.\mvnw.cmd clean verify
```

Criterio de éxito: compila sin warnings, suite completa verde.

---

## Referencias

- [`REQUERIMIENTO.md`](./REQUERIMIENTO.md) — documento maestro: stack, arquitectura,
  puertos, BD y restricciones que gobiernan cada etapa.
- [`docs/plan-produccion-monitoreofletx.md`](./docs/plan-produccion-monitoreofletx.md)
  — plan de etapas (A–E) con checklists de aprobación.
- `../torre_control` — receta de referencia (proyecto hermano en producción).
