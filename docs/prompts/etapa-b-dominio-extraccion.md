# Prompt Claude Code — Etapa B: Dominio y extracción

> **Uso:** pegar este prompt completo en Claude Code, ejecutándolo desde la raíz del repo `monitoreofletx`, sobre el scaffold ya aprobado de la Etapa A (commits hasta `be4ab92`).
> **Referencia:** `REQUERIMIENTO.md` §6/§8, `docs/plan-produccion-monitoreofletx.md` §Etapa B, **`docs/verificacion-tipos-destino-v4.md`** (fuente de verdad de tipos — léelo completo antes de escribir la migración o el record de dominio), `monitoreofletx-consulta-base-v4.sql` (la consulta de extracción ya validada).

---

## Contexto

El scaffold de la Etapa A está aprobado (CI verde, fail-fast verificado, `V1__crea_shedlock.sql` aplicado). Esta etapa agrega la primera pieza de negocio real: el dominio, el adaptador de extracción contra Fletx, y la migración V2 con la tabla de destino completa.

**Regla de oro, igual que en la Etapa A: no reinventes.** El proyecto hermano Controlt (`../torre_control`) ya resolvió el mismo problema con otro dataset — su domain record, su `RowMapper`, y su estructura de fixtures son la referencia. Lee esos archivos reales antes de escribir los tuyos.

**La tabla de destino NO se deriva por inspección propia del SQL.** Ya fue verificada tipo por tipo contra Fletx real por Claude Desktop — está en `docs/verificacion-tipos-destino-v4.md`. Esa tabla es la fuente de verdad; **no la reinterpretes ni "mejores" los tipos** (hay varias sorpresas documentadas ahí — `standby` es `integer` no `boolean`, `document` es `bigint` no `varchar`, `empty_weight` del trailer es `varchar` no numérico — están ahí precisamente porque no son intuitivas).

## Reglas duras (heredadas de `REQUERIMIENTO.md` §9, aplican igual que en la Etapa A)

1. Fail-fast, records inmutables, inyección por constructor, `snake_case`/plural/singular en BD, soft-delete `deleted_at`.
2. **PROHIBIDO H2 y TestContainers.** Fixtures del origen en el schema `test_origen_mf`, con fechas generadas en `@BeforeAll` (nunca en initdb — lección de Controlt: fixtures con `now()` congelado envejecen).
3. `row_hash` — **no se implementa en esta etapa** (es Etapa C), pero el record de dominio debe exponer todos los campos que ese hash necesitará.
4. Ningún secret, ninguna credencial real en fixtures ni en tests.
5. Las migraciones aplicadas (V1) no se tocan. Esta etapa agrega V2, nunca modifica V1.

## Pre-requisito

Lee completo, en este orden: `docs/verificacion-tipos-destino-v4.md` → `monitoreofletx-consulta-base-v4.sql` → el domain record y el `RowMapper` reales de Controlt en `../torre_control/backend/src/main/java/com/fml/controlt/sync/`.

---

## Pasos

### Paso 1 — Migración V2: tabla de destino completa

`backend/src/main/resources/db/migration/V2__crea_tabla_solicitudes.sql`:

- Todas las columnas de `docs/verificacion-tipos-destino-v4.md`, con los tipos ahí indicados **exactamente** (presta especial atención a la sección "⚠️ Hallazgos que rompen el supuesto obvio").
- `solicitud_request` (clave natural, `rq.id`): `bigint NOT NULL`, índice único.
- Agrega las 3 columnas de housekeeping de la sección final del documento: `row_hash varchar(32) NOT NULL`, `deleted_at timestamp NULL`, `sincronizado_at timestamp NOT NULL DEFAULT now()`.
- `eventos_detalle`: tipo `jsonb`.
- Índices de consulta razonables (por `estado_solicitud`, por `fecha_solicitud`, por `deleted_at` — sigue el criterio de Controlt para su tabla equivalente).
- Vista `v_monitoreo_fletx_vigente` que filtra `deleted_at IS NULL`.

- Commit: `feat(db): V2 crea tabla de destino completa (tipos verificados en docs/verificacion-tipos-destino-v4.md)`

**Verificación:** aplica limpio sobre `test_monitoreo_fletx` vacío; `\d solicitudes` (o el nombre de tabla que definas, `snake_case` plural) muestra los tipos exactos del documento de verificación.

### Paso 2 — Record de dominio

`domain/SolicitudFletx.java` (o el nombre que sigas, consistente con el de Controlt): record inmutable con un campo por cada columna de negocio de `docs/verificacion-tipos-destino-v4.md` (excluyendo housekeeping). Tipos Java exactamente los de la columna "Tipo destino / Java" del documento.

`domain/ClaveNatural.java` (o el mecanismo equivalente de Controlt) sobre `solicitudRequest` (`rq.id`).

Javadoc en `otroRecibe`: aclara que es un flag ("¿alguien más recibe la carga?"), no el nombre del receptor — evita que alguien lo confunda con `quienRecibe`.

- Commit: `feat: record de dominio SolicitudFletx + clave natural`

### Paso 3 — SQL de extracción parametrizado

Copia `monitoreofletx-consulta-base-v4.sql` a `backend/src/main/resources/sql/consulta-base.sql`, **sin el `WHERE rq.id IN (...)` de prueba** (ya fue removido en la v4 committeada — verifica que sigue removido) y con el filtro de ventana parametrizado: `WHERE r.created_at >= :fechaInicio` (o el mecanismo de named parameters que use el `JdbcTemplate`/`NamedParameterJdbcTemplate` de Controlt).

Además, castea explícitamente `eventos_detalle` a `jsonb` en el SELECT final (`ej.eventos_detalle::jsonb AS eventos_detalle`) — ver hallazgo en el documento de verificación (json_agg devuelve `json`, no `jsonb`).

- Commit: `feat(sql): consulta de extraccion parametrizada por ventana movil`

### Paso 4 — Adaptador de origen + RowMapper

`infrastructure/adapter/out/origen/JdbcOrigenAdapter.java` (implementa el puerto `OrigenPort`), usando `NamedParameterJdbcTemplate` sobre el `originDataSource` (solo lectura — mismo patrón de Controlt).

`RowMapper` (puede vivir en el propio adaptador o en una clase separada, sigue la convención de Controlt): mapea cada columna del SELECT al record de dominio, con los tipos de `docs/verificacion-tipos-destino-v4.md`. Para `eventos_detalle`: mapea como `String` crudo (el JSON ya viene como texto desde el driver JDBC cuando la columna es `jsonb`/`json`) — no lo deserialices a un POJO en esta etapa, se decide en Etapa C si Analítica lo requiere.

- Commit: `feat: adaptador JDBC de origen con RowMapper`

### Paso 5 — Fixtures y tests de mapeo

Fixtures del origen en `test_origen_mf`: sigue la estructura de `Estructura_tablas_fletx.sql` (adjunta en el repo o en `../torre_control` si Controlt la tiene) para las tablas mínimas necesarias (requests, bookings, people, vehicles, requeststatuses, events, etc.). **Fechas generadas en `@BeforeAll`**, nunca en el script de init del contenedor.

Tests: verifica que el `RowMapper` puebla correctamente cada campo del record, con foco en los campos marcados ⚠️ en el documento de verificación (que no se conviertan accidentalmente al tipo "intuitivo" equivocado) y en `eventos_detalle` (JSON válido, incluye el evento génesis sin estado anterior — ver `[B10]` en el SQL).

- Commit: `test: fixtures de origen y tests de mapeo del RowMapper`

**Verificación:**
```
docker compose -f docker-compose.test.yml up -d
./mvnw -f backend/pom.xml clean verify
```
Todos los tests verdes.

### Paso 6 — EXPLAIN contra Fletx real (lo hace Claude Desktop, no tú)

No ejecutes este paso — repórtalo como pendiente en tu entrega final. Claude Desktop revisará el plan de ejecución del SELECT parametrizado contra Fletx real antes de aprobar la etapa.

### Paso 7 — Push y reporte

1. `git log --oneline` → confirma los commits de esta etapa.
2. `git push`.
3. Reporta: resultado de `mvnw verify`, lista de commits, cualquier desviación con su justificación, y confirma explícitamente que revisaste `docs/verificacion-tipos-destino-v4.md` antes de escribir V2 (cita al menos 2 de los hallazgos ⚠️ para confirmar que los aplicaste).

---

## Fuera de alcance de este prompt

- `row_hash`, upsert, soft-delete, scheduler — Etapa C.
- Deserialización estructurada de `eventos_detalle` — se decide en Etapa C si es necesaria.
- `freight_ministries` (RNDC) y FK de `paga_id` — pendientes [P1]/[P2], fuera de esta etapa.

## Checklist de aprobación de la Etapa B

- [ ] Migración V2 aplicada limpio; tipos verificados contra `docs/verificacion-tipos-destino-v4.md` columna por columna (no por inspección propia).
- [ ] Record de dominio con un campo por columna de negocio, tipos Java correctos (especial atención a los 6 hallazgos ⚠️).
- [ ] `eventos_detalle` casteado a `jsonb` en el SELECT; mapeado como `String` crudo en el `RowMapper`.
- [ ] Tests de mapeo verdes contra PostgreSQL real (`test_origen_mf`/`test_monitoreo_fletx`), fixtures con fechas en `@BeforeAll`.
- [ ] `EXPLAIN` del SELECT parametrizado revisado contra Fletx real por Claude Desktop (pendiente, no lo hace Claude Code).
- [ ] Push realizado; reporte confirma revisión del documento de verificación de tipos.
