package com.fml.monitoreofletx.sync.application.port.out;

/**
 * Resultado del upsert por corrida de sincronización.
 * Permite al servicio loguear y registrar métricas con granularidad real.
 */
public record UpsertStats(int insertados, int actualizados, int omitidos) {

    public static final UpsertStats VACIO = new UpsertStats(0, 0, 0);

    /** Filas con escritura efectiva en BD (INSERT + UPDATE). */
    public int escritas() { return insertados + actualizados; }

    /** Total de solicitudes procesadas en la corrida. */
    public int total()    { return insertados + actualizados + omitidos; }
}
