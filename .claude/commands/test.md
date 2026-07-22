# /test — Ejecutar la suite de pruebas

Levanta PostgreSQL de pruebas en Docker (schemas `test_monitoreo_fletx` y
`test_origen_mf`) y ejecuta los tests. NUNCA usar H2 ni TestContainers.

```powershell
# 1. PostgreSQL de pruebas (puerto 5438)
docker compose -f docker-compose.test.yml up -d

# 2. Esperar a que esté listo y correr tests
cd backend
.\mvnw.cmd test
```

Al terminar:
```powershell
docker compose -f docker-compose.test.yml down -v
```

Criterio de éxito: 100% de los tests pasan. Incluir siempre el test de idempotencia
del upsert (insert / no-op sin cambios / update con cambio + `updated_at`) una vez
exista el ciclo de sincronización (Etapa C).
