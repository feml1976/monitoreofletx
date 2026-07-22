package com.fml.monitoreofletx.sync.domain;

/**
 * Clave natural de negocio de una solicitud: {@code solicitud_request} (rq.id en Fletx).
 * A diferencia de Controlt (clave compuesta no_servicio+planilla+remesa), aquí la
 * clave natural es una única columna NOT NULL: el índice único del destino no
 * necesita COALESCE de sentinel.
 */
public record ClaveNatural(long solicitudRequest) {

    public static ClaveNatural de(SolicitudFletx s) {
        return new ClaveNatural(s.solicitudRequest());
    }
}
