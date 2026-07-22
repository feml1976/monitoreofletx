# Prompt Claude Code — Etapa C: Proyección completa

> **Uso:** pegar este prompt completo en Claude Code, ejecutándolo desde la raíz del repo `monitoreofletx`, sobre la Etapa B ya aprobada (commits hasta `d4b9c93`, 18/18 tests verdes con el fix del CTE `plantas`).
> **Referencia:** `REQUERIMIENTO.md` §6, `docs/plan-produccion-monitoreofletx.md` §Etapa C, y el código **real** de Controlt en `../torre_control/backend/src/main/java/com/fml/controlt/sync/infrastructure/adapter/out/proyeccion/JdbcTorreControlProyeccionAdapter.java` — esta etapa es, literalmente, adaptar ese archivo.

---

## Contexto

Dominio (`SolicitudFletx`), extracción (`JdbcOrigenAdapter` + `RowMapper`) y migración V2 (tabla de destino completa, tipos verificados) ya están aprobados. Falta el corazón del ETL: calcular si cada solicitud cambió, escribirla de forma idempotente, dar de baja las que salieron de la ventana, y programar el ciclo.

**Regla de oro, igual que en las etapas anteriores: no reinventes.** El adaptador de proyección de Controlt ya resolvió exactamente este problema (mismo patrón: `computeHash`, upsert con `ON CONFLICT`, `marcarObsoletos` con `unnest`, métricas con tag `outcome`). Lee ese archivo completo antes de escribir el tuyo. La diferencia principal es de escala: Controlt hashea ~26 campos elegidos; `SolicitudFletx` tiene ~119 campos de negocio y aquí se hashean **todos**, sin selección — no hay razón para excluir ninguno, y excluir alguno a mano es una fuente de bugs silenciosos (una solicitud "cambia" en Fletx pero el hash no lo detecta).

## Reglas duras (heredadas, aplican igual que en las etapas anteriores)

1. **`row_hash` se calcula en Java, nunca con `md5()` de SQL** (formato de serialización incompatible — lección de Controlt, ya está en el comentario de la migración V2).
2. Orden canónico **fijo** de campos al construir el hash (el mismo orden siempre, documentado en el código) — si el orden varía entre ejecuciones el hash es inútil.
3. Sentinel byte NUL para valores nulos (no usar string vacío ni omitir el campo — un nulo debe hashear distinto de un string vacío).
4. Fechas serializadas en ISO-8601 antes de hashear (`LocalDateTime`/`LocalDate`, nunca `toString()` default si no es ISO-8601 explícito).
5. `eventos_detalle` (el JSON crudo como `String`) entra al hash tal cual — un evento nuevo en Fletx debe cambiar el hash y disparar el `UPDATE`.
6. Upsert: `INSERT ... ON CONFLICT (solicitud_request) DO UPDATE SET ... WHERE solicitudes.row_hash IS DISTINCT FROM EXCLUDED.row_hash OR solicitudes.deleted_at IS NOT NULL` — permite skip masivo (no reescribe filas sin cambios) y resurrección automática (una fila soft-deleted que reaparece en la ventana se reactiva sola).
7. `marcarObsoletos`: anti-join vía `unnest` de un array de IDs presentes en el batch — **nunca** `NOT IN` con lista literal ni tablas temporales.
8. Métrica de éxito explícita: un **counter** (no un timer) con tag `outcome`, que se incrementa SOLO en el camino feliz (insertado/actualizado/omitido) — **nunca en el `catch`**. Lección de Controlt v1.4.0: el timer de duración se detiene también en fallos, así que no sirve para alertar "sync estancado"; el counter sí, porque solo crece cuando el ciclo realmente progresó.
9. `lock-at-most-for` del `ShedLock` debe ser menor que el intervalo del cron (ya configurado en `application.yml` desde la Etapa A: `PT14M` con cron de 15 min — no lo cambies sin razón).
10. Log estructurado con un `run_id` (UUID por ciclo) que amarre entrada/salida/error del mismo ciclo — nunca `System.out.println`, nunca datos personales en logs (ver columnas `[P3]` del documento de tipos).

## Pre-requisito

Lee completo: `../torre_control/.../JdbcTorreControlProyeccionAdapter.java` → el domain record `SolicitudFletx` (Etapa B) → `docs/verificacion-tipos-destino-v4.md` (para saber qué campos son `bigint`/`Integer`/`Boolean` al construir el hash, especialmente los 6 hallazgos ⚠️).

---

## Pasos

### Paso 1 — `computeHash`

En el adaptador de proyección (o una clase colaboradora, según el patrón de Controlt): método que recibe `SolicitudFletx` y devuelve el `row_hash` (MD5, hexadecimal, 32 caracteres). Todos los campos de negocio, orden canónico fijo, sentinel NUL, fechas ISO-8601, `eventos_detalle` incluido como texto crudo.

- Commit: `feat: computeHash MD5 sobre todos los campos de negocio de SolicitudFletx`

**Verificación:** test unitario que cambia un solo campo a la vez (cubre representativamente varios tipos: `String`, `Long`, `Integer`, `Boolean`, `BigDecimal`, `LocalDateTime`, `LocalDate`, y el `String` de `eventos_detalle`) y confirma que el hash cambia. Incluye explícitamente los 6 campos ⚠️ del documento de tipos.

### Paso 2 — Upsert

`JdbcProyeccionAdapter` (implementa `ProyeccionPort`): `upsert(List<SolicitudFletx>)` con batch `INSERT ... ON CONFLICT` como se describe en la regla dura 6. Devuelve estadísticas (insertados/actualizados/omitidos), mismo patrón `UpsertStats` de Controlt.

- Commit: `feat: upsert idempotente con row_hash y resurreccion de soft-deletes`

**Verificación:** test de integración: insertar nuevo (contado como insertado), reinsertar sin cambios (contado como omitido, `updated_at`/`sincronizado_at` no debe cambiar), modificar un campo y reinsertar (contado como actualizado), reinsertar una fila con `deleted_at` no nulo (debe resucitar: `deleted_at` vuelve a `NULL`).

### Paso 3 — `marcarObsoletos`

Anti-join vía `unnest` sobre el array de `solicitud_request` presentes en el batch actual, marcando `deleted_at = now()` en las filas de `solicitudes` (no `deleted_at` ya) que no aparecen en ese array. Alcance: solo dentro del universo ya sincronizado (no tocar filas fuera de la ventora histórica del negocio — sigue el criterio de Controlt).

- Commit: `feat: soft-delete de solicitudes ausentes de la ventana via unnest`

**Verificación:** test de integración: una solicitud que estaba en la ventana y ya no aparece (ventana avanzó) queda con `deleted_at` no nulo; una solicitud que sigue en la ventana no se toca.

### Paso 4 — Servicio orquestador + métricas + scheduler

Servicio que implementa el puerto de entrada (caso de uso de sincronización): calcula la ventana (`SYNC_VENTANA_DIAS`), invoca origen → mapea → upsert → marcarObsoletos, todo bajo un `run_id` (UUID) logueado en cada paso. Métrica `monitoreofletx.sync.solicitudes` (counter, tag `outcome` con valores `insertado`/`actualizado`/`omitido`) — **incrementar solo si el ciclo llega a ese punto sin excepción**, nunca en `catch`. Adaptador de entrada con `@Scheduled(cron = "${monitoreofletx.sync.cron}")` + `@SchedulerLock(name = "sync-solicitudes", lockAtMostFor = "${...:PT14M}", lockAtLeastFor = "${...:PT1M}")` (los placeholders de `application.yml` ya existen desde la Etapa A).

- Commit: `feat: servicio orquestador con metricas de outcome y scheduler con ShedLock`

**Verificación:** test de integración de concurrencia: dos invocaciones simultáneas del ciclo, solo una debe ejecutar el cuerpo (ShedLock funcionando); test que fuerza una excepción a mitad del ciclo y confirma que la métrica de `outcome` NO se incrementó para esa iteración.

### Paso 5 — Suite completa y verificación final

```
docker compose -f docker-compose.test.yml up -d
./mvnw -f backend/pom.xml clean verify
```

Cubre explícitamente en la suite: hash sensible a cada tipo de campo, insert/update/skip, soft-delete, resurrección, fuera-de-ventana (una solicitud fuera de la ventana no se toca ni se soft-deletea), concurrencia ShedLock, consulta contra la vista `v_monitoreo_fletx_vigente` (solo trae `deleted_at IS NULL`).

- Commit: `test: suite de integracion completa del ciclo de sincronizacion`

### Paso 6 — Push y reporte

1. `git log --oneline` → commits de esta etapa.
2. `git push`.
3. Reporta: resultado de `mvnw verify` (con conteo de tests), lista de commits, confirmación explícita de que el counter de `outcome` NO se incrementa en fallo (cita el test que lo prueba), y cualquier desviación con su justificación.

---

## Fuera de alcance de este prompt

- Ejecución contra Fletx real / despliegue en `Linux-XPS` — Etapa D.
- `freight_ministries` (RNDC) y confirmación semántica de `paga_id` — pendientes `[P1]`/`[P2]`.
- Tratamiento de datos personales por campo (Ley 1581) — pendiente `[P3]`, no bloqueante para esta etapa.
- Backfill histórico, dashboard, alertas — Etapa E.

## Checklist de aprobación de la Etapa C

- [ ] `computeHash` cubre el 100% de los campos de negocio, orden canónico fijo, sentinel NUL, ISO-8601, `eventos_detalle` incluido.
- [ ] Upsert con `ON CONFLICT` + condición de `row_hash`/`deleted_at`; resurrección probada.
- [ ] `marcarObsoletos` con `unnest`; fuera-de-ventana no se toca.
- [ ] Métrica `outcome` verificada: no se incrementa en fallo (test explícito).
- [ ] ShedLock probado con concurrencia real (dos invocaciones simultáneas).
- [ ] Suite completa verde contra PostgreSQL real; 0 warnings de compilación.
- [ ] Push realizado.
