package com.fml.monitoreofletx.sync.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT14M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(
            @Qualifier("datamartDataSource") DataSource dataSource,
            @Value("${monitoreofletx.flyway.schema:monitoreo_fletx}") String schema) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(schema + ".shedlock")
                        .usingDbTime()
                        .build()
        );
    }
}
