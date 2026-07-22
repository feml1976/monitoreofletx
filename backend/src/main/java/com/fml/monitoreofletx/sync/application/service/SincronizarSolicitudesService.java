package com.fml.monitoreofletx.sync.application.service;

import com.fml.monitoreofletx.sync.application.port.in.SincronizarSolicitudesUseCase;
import com.fml.monitoreofletx.sync.application.port.out.OrigenPort;
import com.fml.monitoreofletx.sync.application.port.out.ProyeccionPort;
import com.fml.monitoreofletx.sync.domain.ClaveNatural;
import com.fml.monitoreofletx.sync.infrastructure.config.SyncProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SincronizarSolicitudesService implements SincronizarSolicitudesUseCase {

    private static final Logger log = LoggerFactory.getLogger(SincronizarSolicitudesService.class);

    private final OrigenPort      origenPort;
    private final ProyeccionPort  proyeccionPort;
    private final SyncProperties  props;
    private final Counter         insertadosCounter;
    private final Counter         actualizadosCounter;
    private final Counter         omitidosCounter;

    public SincronizarSolicitudesService(
            OrigenPort     origenPort,
            ProyeccionPort proyeccionPort,
            SyncProperties props,
            MeterRegistry  registry) {
        this.origenPort         = origenPort;
        this.proyeccionPort     = proyeccionPort;
        this.props              = props;
        this.insertadosCounter  = Counter.builder("monitoreofletx.sync.solicitudes")
                .tag("outcome", "insertado").register(registry);
        this.actualizadosCounter = Counter.builder("monitoreofletx.sync.solicitudes")
                .tag("outcome", "actualizado").register(registry);
        this.omitidosCounter    = Counter.builder("monitoreofletx.sync.solicitudes")
                .tag("outcome", "omitido").register(registry);
    }

    @Override
    public void sincronizar() {
        var runId  = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var fin    = LocalDateTime.now();
        var inicio = fin.minusDays(props.ventanaDias());

        log.info("sync.start run_id={} ventana=[{}, {})", runId, inicio, fin);

        try {
            var solicitudes = origenPort.consultar(inicio, fin);
            log.info("sync.leidos run_id={} count={}", runId, solicitudes.size());

            var stats = proyeccionPort.upsert(solicitudes);

            // Guarda de seguridad: un origen vacio es anomalo. Si ocurriera, NO se
            // marcan obsoletos — evita que una falla silenciosa del origen borre
            // masivamente la proyeccion vigente.
            int obsoletos;
            if (solicitudes.isEmpty()) {
                obsoletos = 0;
                log.warn("sync.omite_obsoletos run_id={} motivo=origen_vacio", runId);
            } else {
                List<ClaveNatural> clavesVigentes = solicitudes.stream().map(ClaveNatural::de).toList();
                obsoletos = proyeccionPort.marcarObsoletos(inicio, fin, clavesVigentes);
            }

            // Metrica de outcome: solo se incrementa aqui, tras completar upsert y
            // marcarObsoletos sin excepcion — nunca en el catch (lo contrario haria
            // que un ciclo fallido pareciera progreso real para las alertas).
            insertadosCounter.increment(stats.insertados());
            actualizadosCounter.increment(stats.actualizados());
            omitidosCounter.increment(stats.omitidos());

            log.info("sync.fin run_id={} leidos={} insertados={} actualizados={} omitidos={} obsoletos={}",
                    runId, solicitudes.size(),
                    stats.insertados(), stats.actualizados(), stats.omitidos(), obsoletos);
        } catch (Exception e) {
            log.error("sync.error run_id={} ventana=[{}, {}) mensaje={}",
                    runId, inicio, fin, e.getMessage(), e);
            throw e;
        }
    }
}
