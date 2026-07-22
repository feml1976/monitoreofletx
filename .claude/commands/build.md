# /build — Compilar y verificar

Compila el backend y corre las verificaciones estáticas (sin tests de integración).

```powershell
cd backend
.\mvnw.cmd clean verify -DskipTests
```

Criterio de éxito: compila sin errores **ni warnings**. Si hay warnings, resuélvelos
antes de continuar (no los ignores).
