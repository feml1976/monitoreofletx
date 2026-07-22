package com.fml.monitoreofletx.sync.application.port.out;

import com.fml.monitoreofletx.sync.domain.ClaveNatural;
import com.fml.monitoreofletx.sync.domain.SolicitudFletx;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Puerto de salida: escribe la proyección de solicitudes en el datamart destino.
 * El upsert es idempotente: no reescribe filas cuyo row_hash no haya cambiado.
 * IMPORTANTE: la tabla solicitudes SOLO debe escribirse vía la app; cualquier
 * refresh SQL directo con md5() en BD produciría hashes incomparables con los
 * calculados aquí (formato de serialización diferente).
 */
public interface ProyeccionPort {
    UpsertStats upsert(List<SolicitudFletx> solicitudes);

    /**
     * Marca deleted_at=now() en filas cuya fecha_solicitud está dentro de
     * [ventanaInicio, ventanaFin) y cuya clave natural NO está entre las
     * claves vigentes del ciclo. Filas fuera de la ventana no se tocan.
     * Retorna la cantidad de filas marcadas.
     */
    int marcarObsoletos(LocalDateTime ventanaInicio, LocalDateTime ventanaFin, Collection<ClaveNatural> clavesVigentes);
}
