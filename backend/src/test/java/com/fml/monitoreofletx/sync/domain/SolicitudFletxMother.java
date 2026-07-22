package com.fml.monitoreofletx.sync.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Object mother de test: construye instancias de SolicitudFletx con los 119
 * campos poblados, para los tests de integración del adaptador de proyección,
 * el servicio orquestador y el scheduler (evita repetir el constructor de
 * 119 posiciones en cada suite).
 */
public final class SolicitudFletxMother {

    private SolicitudFletxMother() {}

    public static SolicitudFletx base(long solicitudRequest) {
        LocalDate fecha = LocalDate.of(2026, 1, 10);
        LocalDateTime fh = LocalDateTime.of(2026, 1, 15, 8, 30);
        return new SolicitudFletx(
                1000L, solicitudRequest, "Activo", "En proceso", 20001L, 10001L,
                "ABC123", "Kenworth", "T800", "Blanco", "Planchon", "Propio", "6x4", "Tractocamion", 34, 40,
                "R99999", "Sencillo", "Trocal", "Gris", "2.5 Ton", 2015, 3,
                "CC", 1020304050L, "Juan Carlos", "Perez Gomez", "Calle 1", "Bogota", "Cundinamarca", "3001112233", "juan@test.com",
                "Maria Perez", "3002223344", "Calle 2", fecha, fecha.plusYears(5),
                3001234567L, 3007654321L, 3009876543L,
                500111222L, 3, "Ana", "Torres", 500333444L, "Hilda Holder", 500555666L, 7, "Oscar", "Mendez", 500777888L, "Rosa TeneTr",
                "Cliente Demo S.A.", "Transporte", "Carga seca", "Generador X", "NIT", 900123456L, 5, "Calle 3", "3003334455", "3004445566", "Cali", "Valle",
                "ClientePaga S.A.", "Ana Receptora", "3011112222", true,
                "Directo", "Sider1", "Nacional", fh, fh.plusHours(1),
                new BigDecimal("150000.50"), new BigDecimal("200000.75"), fh.plusHours(2),
                5_000_000L, fh, fh.plusHours(3),
                new BigDecimal("1234567.89"), 15, 2,
                "REM-001", "MAN-001", "QR123", false, 2, true, 3600L, false, 7001L, 8001L, "Terrestre", "EmpresaX",
                "Bogota", "Planta Fusagasuga", "Medellin", "Bodega Cali", "BOG-MED", "COD-001",
                fh.plusHours(4), true, "Resuelta", "Retraso menor", fh.plusHours(5),
                "[{\"evento_id\":1,\"estado_anterior\":null,\"estado_actual\":\"Creado\"}]",
                2L, 2L, 0L, fh.plusHours(6), true,
                555L, 100_000L, 50_000L, 5_000L, 1_200_000L, true, true, false
        );
    }

    /**
     * Copia {@code base} reemplazando únicamente el componente {@code nombreComponente}.
     */
    public static SolicitudFletx con(SolicitudFletx base, String nombreComponente, Object valor) {
        try {
            var componentes = SolicitudFletx.class.getRecordComponents();
            var tipos = new Class<?>[componentes.length];
            var valores = new Object[componentes.length];
            for (int i = 0; i < componentes.length; i++) {
                var c = componentes[i];
                tipos[i] = c.getType();
                valores[i] = c.getName().equals(nombreComponente) ? valor : c.getAccessor().invoke(base);
            }
            return SolicitudFletx.class.getDeclaredConstructor(tipos).newInstance(valores);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
