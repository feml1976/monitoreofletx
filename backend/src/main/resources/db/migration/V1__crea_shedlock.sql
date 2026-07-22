-- =============================================================================
-- V1 — Tabla de coordinacion distribuida para ShedLock.
-- Garantiza que solo una instancia ejecute el job de sincronizacion a la vez.
-- Schema: ${schema} (mismo que la proyeccion, para no requerir permisos adicionales).
--
-- Deviacion respecto a REQUERIMIENTO.md SS8 (que define V1 = tabla de destino,
-- V2 = shedlock): en esta Etapa A se invierte el orden. La tabla de destino
-- completa (~90 columnas derivadas de monitoreofletx-consulta-base-v4.sql)
-- requiere que Claude Desktop verifique tipo por tipo contra Fletx real
-- primero (Etapa B) — las migraciones aplicadas jamas se modifican, y crear
-- V1 a ciegas arriesga tener que corregirla despues con una migracion nueva
-- en vez de una V2 completa desde el principio. Ver docs/prompts/etapa-a-scaffold-ci.md.
-- =============================================================================
CREATE TABLE IF NOT EXISTS ${schema}.shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL,
    PRIMARY KEY (name)
);
