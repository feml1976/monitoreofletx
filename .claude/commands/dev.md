# /dev — Levantar entorno de desarrollo

```powershell
# 1. Variables de entorno (primera vez)
Copy-Item .env.example .env   # completar valores reales; .env NO se commitea

# 2. PostgreSQL de desarrollo (schema monitoreo_fletx) en 5438
docker compose up -d

# 3. Backend headless (Flyway migra al arrancar; management/actuator en 8098)
cd backend
.\mvnw.cmd spring-boot:run
```

Verificación rápida: `Invoke-RestMethod http://localhost:8098/actuator/health` → `status: UP`.

Notas:
- El job arranca y queda esperando el cron (cada 15 min por defecto). Para disparar
  una corrida manual en dev, expón un endpoint de management protegido o usa un
  perfil `dev` que ejecute una sincronización al inicio (Etapa C).
- El origen real es read-only y externo: en dev puedes apuntar `originDataSource` al
  schema `test_origen_mf` del PostgreSQL local con fixtures.
