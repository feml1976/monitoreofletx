package com.fml.monitoreofletx.sync.application.port.out;

import com.fml.monitoreofletx.sync.domain.SolicitudFletx;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Puerto de salida: consulta al origen (Fletx, SOLO LECTURA).
 * Retorna las solicitudes creadas dentro de la ventana [inicio, fin).
 * El adaptador concreto no puede emitir ningún DDL ni escritura contra el origen.
 */
public interface OrigenPort {
    List<SolicitudFletx> consultar(LocalDateTime ventanaInicio, LocalDateTime ventanaFin);
}
