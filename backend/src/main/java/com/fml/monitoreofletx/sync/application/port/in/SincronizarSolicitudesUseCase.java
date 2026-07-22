package com.fml.monitoreofletx.sync.application.port.in;

/**
 * Puerto de entrada: disparar una sincronización de la proyección de solicitudes.
 * El caso de uso consulta el origen, calcula la ventana y hace upsert en el destino.
 */
public interface SincronizarSolicitudesUseCase {
    void sincronizar();
}
