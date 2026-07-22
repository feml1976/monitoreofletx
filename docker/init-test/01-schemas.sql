-- Init de PRUEBAS (se ejecuta una vez al crear el contenedor).
-- Crea los schemas que usan los tests de integracion.
CREATE SCHEMA IF NOT EXISTS test_monitoreo_fletx;  -- destino (lo migra Flyway en test)
CREATE SCHEMA IF NOT EXISTS test_origen_mf;         -- stand-in del origen (read-only en prod)
