# /new-feature — Flujo para agregar una feature

Sigue este flujo respetando la arquitectura hexagonal y las reglas del proyecto.

1. **Entender el cambio**: ¿toca dominio, aplicación o infraestructura? ¿Modifica un
   contrato compartido (schema `monitoreo_fletx`, puerto, `application.yml`)? Si hay
   breaking change, declara el blast radius antes de tocar código.
2. **Dominio primero**: si cambia el modelo, ajusta el `record` y sus invariantes.
3. **Puerto antes que adaptador**: define/ajusta la interfaz en `application/port/out`
   (o `port/in`) antes de implementarla.
4. **Adaptador**: implementa en `infrastructure/adapter/...`. Consultas parametrizadas,
   manejo de error explícito, logging en boundaries sin PII.
5. **Migración si aplica**: nueva `V{n}__descripcion.sql` en `db/migration` (solo destino).
   Nunca editar una migración ya aplicada.
6. **Tests**: unitario del servicio (puertos mockeados) + integración contra PG real
   (Docker). Declara la estrategia de testing.
7. **Verificar**: `/build` y `/test` en verde. Sin warnings, sin TODOs no señalados.
8. **Documentar**: si cambió arquitectura o convención, actualiza `.claude/CLAUDE.md`
   y considera un ADR.

Reglas que no se rompen: nada de escritura/DDL en el origen; upsert idempotente con
`row_hash`; sin secretos en código; sin H2/TestContainers.
