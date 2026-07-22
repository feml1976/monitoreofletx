package com.fml.monitoreofletx.sync.infrastructure.adapter.in.scheduler;

import com.fml.monitoreofletx.sync.application.port.in.SincronizarSolicitudesUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SincronizacionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SincronizacionScheduler.class);

    private final SincronizarSolicitudesUseCase useCase;

    public SincronizacionScheduler(SincronizarSolicitudesUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${monitoreofletx.sync.cron}")
    @SchedulerLock(
            name           = "monitoreofletx_sync",
            lockAtMostFor  = "${monitoreofletx.sync.lock-at-most-for}",
            lockAtLeastFor = "${monitoreofletx.sync.lock-at-least-for}"
    )
    public void ejecutar() {
        log.info("scheduler.trigger");
        try {
            useCase.sincronizar();
        } catch (Exception e) {
            log.error("scheduler.error mensaje={}", e.getMessage(), e);
        }
    }
}
