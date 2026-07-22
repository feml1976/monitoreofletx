package com.fml.monitoreofletx.sync.application.service;

import com.fml.monitoreofletx.sync.application.port.out.OrigenPort;
import com.fml.monitoreofletx.sync.application.port.out.ProyeccionPort;
import com.fml.monitoreofletx.sync.application.port.out.UpsertStats;
import com.fml.monitoreofletx.sync.domain.ClaveNatural;
import com.fml.monitoreofletx.sync.domain.SolicitudFletx;
import com.fml.monitoreofletx.sync.infrastructure.config.SyncProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.fml.monitoreofletx.sync.domain.SolicitudFletxMother.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SincronizarSolicitudesServiceTest {

    @Mock OrigenPort     origenPort;
    @Mock ProyeccionPort proyeccionPort;

    SincronizarSolicitudesService service;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        var props = new SyncProperties(
                7,
                "0 0/15 * * * *",
                Duration.ofMinutes(14),
                Duration.ofMinutes(1));
        registry = new SimpleMeterRegistry();
        service = new SincronizarSolicitudesService(origenPort, proyeccionPort, props, registry);
        // Default stub para llamadas que no validan el retorno
        lenient().when(proyeccionPort.upsert(any())).thenReturn(UpsertStats.VACIO);
    }

    @Test
    void consultaOrigenConVentanaDias() {
        when(origenPort.consultar(any(), any())).thenReturn(List.of());

        service.sincronizar();

        ArgumentCaptor<LocalDateTime> inicioCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> finCaptor    = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(origenPort).consultar(inicioCaptor.capture(), finCaptor.capture());

        LocalDateTime inicio = inicioCaptor.getValue();
        LocalDateTime fin    = finCaptor.getValue();

        assertThat(fin).isAfter(inicio);
        long dias = java.time.temporal.ChronoUnit.DAYS.between(inicio, fin);
        assertThat(dias).isBetween(6L, 7L);
    }

    @Test
    void delegaUpsertAlPuertoDeProyeccion() {
        var solicitud = base(1L);
        when(origenPort.consultar(any(), any())).thenReturn(List.of(solicitud));
        when(proyeccionPort.upsert(List.of(solicitud))).thenReturn(new UpsertStats(1, 0, 0));

        service.sincronizar();

        verify(proyeccionPort).upsert(List.of(solicitud));
    }

    @Test
    void cuandoOrigenDevuelveVacioLlamaUpsertConListaVacia() {
        when(origenPort.consultar(any(), any())).thenReturn(List.of());

        service.sincronizar();

        verify(proyeccionPort).upsert(List.of());
    }

    @Test
    void sincronizarFallaFastSiOrigenLanzaExcepcion() {
        when(origenPort.consultar(any(), any()))
                .thenThrow(new RuntimeException("origen no disponible"));

        assertThatThrownBy(() -> service.sincronizar())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("origen no disponible");

        verifyNoInteractions(proyeccionPort);
    }

    @Test
    void pasaListaCompletaAlUpsert() {
        var solicitudes = List.of(base(1L), base(2L), base(3L));
        when(origenPort.consultar(any(), any())).thenReturn(solicitudes);
        when(proyeccionPort.upsert(solicitudes)).thenReturn(new UpsertStats(3, 0, 0));
        when(proyeccionPort.marcarObsoletos(any(), any(), any())).thenReturn(0);

        service.sincronizar();

        verify(proyeccionPort).upsert(solicitudes);
        verify(proyeccionPort).marcarObsoletos(any(), any(), any());
        verifyNoMoreInteractions(proyeccionPort);
    }

    @Test
    void marcarObsoletosRecibeVentanaYClavesDelResultado() {
        var solicitud1 = base(1L);
        var solicitud2 = base(2L);
        when(origenPort.consultar(any(), any())).thenReturn(List.of(solicitud1, solicitud2));
        when(proyeccionPort.upsert(any())).thenReturn(new UpsertStats(2, 0, 0));
        when(proyeccionPort.marcarObsoletos(any(), any(), any())).thenReturn(1);

        service.sincronizar();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ClaveNatural>> clavesCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> inicioCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> finCaptor    = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(proyeccionPort).marcarObsoletos(inicioCaptor.capture(), finCaptor.capture(), clavesCaptor.capture());

        assertThat(finCaptor.getValue()).isAfter(inicioCaptor.getValue());
        assertThat(clavesCaptor.getValue())
                .containsExactlyInAnyOrder(ClaveNatural.de(solicitud1), ClaveNatural.de(solicitud2));
    }

    @Test
    void origenVacioNoInvocaMarcarObsoletos() {
        when(origenPort.consultar(any(), any())).thenReturn(List.of());

        service.sincronizar();

        verify(proyeccionPort, never()).marcarObsoletos(any(), any(), any());
    }

    // --- Metrica de outcome: nunca se incrementa en el catch ---

    @Test
    void cicloExitoso_incrementaContadoresDeOutcomeSegunStats() {
        var solicitud = base(1L);
        when(origenPort.consultar(any(), any())).thenReturn(List.of(solicitud));
        when(proyeccionPort.upsert(any())).thenReturn(new UpsertStats(1, 2, 3));
        when(proyeccionPort.marcarObsoletos(any(), any(), any())).thenReturn(0);

        service.sincronizar();

        assertThat(contador("insertado")).isEqualTo(1.0);
        assertThat(contador("actualizado")).isEqualTo(2.0);
        assertThat(contador("omitido")).isEqualTo(3.0);
    }

    @Test
    void fallaEnUpsert_noIncrementaNingunContadorDeOutcome() {
        when(origenPort.consultar(any(), any())).thenReturn(List.of(base(1L)));
        when(proyeccionPort.upsert(any())).thenThrow(new RuntimeException("fallo de escritura"));

        assertThatThrownBy(() -> service.sincronizar()).isInstanceOf(RuntimeException.class);

        assertThat(contador("insertado")).isZero();
        assertThat(contador("actualizado")).isZero();
        assertThat(contador("omitido")).isZero();
    }

    @Test
    void fallaEnMarcarObsoletos_noIncrementaNingunContadorDeOutcome() {
        when(origenPort.consultar(any(), any())).thenReturn(List.of(base(1L)));
        when(proyeccionPort.upsert(any())).thenReturn(new UpsertStats(1, 0, 0));
        when(proyeccionPort.marcarObsoletos(any(), any(), any()))
                .thenThrow(new RuntimeException("fallo al marcar obsoletos"));

        assertThatThrownBy(() -> service.sincronizar()).isInstanceOf(RuntimeException.class);

        assertThat(contador("insertado"))
                .as("el upsert si conto 1 insertado, pero el fallo posterior en marcarObsoletos no debe dejar la metrica a medias")
                .isZero();
    }

    private double contador(String outcome) {
        var counter = registry.find("monitoreofletx.sync.solicitudes").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
