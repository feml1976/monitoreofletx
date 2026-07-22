package com.fml.monitoreofletx.sync.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("monitoreofletx.sync")
public record SyncProperties(
        @DefaultValue("7")       int ventanaDias,
        @DefaultValue("0 0/15 * * * *") String cron,
        @DefaultValue("PT14M")   Duration lockAtMostFor,
        @DefaultValue("PT1M")    Duration lockAtLeastFor
) {}
