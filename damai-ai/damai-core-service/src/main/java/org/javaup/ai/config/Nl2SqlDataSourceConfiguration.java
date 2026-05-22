package org.javaup.ai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class Nl2SqlDataSourceConfiguration {

    private final Nl2SqlProperties nl2sqlProperties;
    private final ThreadPoolProperties threadPoolProperties;

    @Bean
    @ConditionalOnProperty(prefix = "damai.ai.nl2sql", name = "enabled", havingValue = "true", matchIfMissing = false)
    public DataSource nl2sqlDataSource() {
        Nl2SqlProperties.DataSource ds = nl2sqlProperties.getDatasource();
        if (!StringUtils.hasText(ds.getUrl())) {
            log.info("NL2SQL datasource URL not configured, skipping connection pool creation");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:nl2sql_placeholder;DB_CLOSE_DELAY=-1");
            config.setMaximumPoolSize(1);
            return new HikariDataSource(config);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ds.getUrl());
        config.setDriverClassName(ds.getDriverClassName());
        if (StringUtils.hasText(ds.getUsername())) {
            config.setUsername(ds.getUsername());
        }
        if (StringUtils.hasText(ds.getPassword())) {
            config.setPassword(ds.getPassword());
        }
        config.setReadOnly(true);
        config.setMaximumPoolSize(threadPoolProperties.getNl2sql().getMaxPoolSize());
        config.setConnectionTimeout(threadPoolProperties.getNl2sql().getConnectionTimeoutMs());
        config.setIdleTimeout(threadPoolProperties.getNl2sql().getIdleTimeoutMs());
        config.setMaxLifetime(threadPoolProperties.getNl2sql().getMaxLifetimeMs());
        config.setPoolName("nl2sql-pool");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "50");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "1024");
        log.info("NL2SQL connection pool created: maxPoolSize={}, connectionTimeout={}ms",
                config.getMaximumPoolSize(), config.getConnectionTimeout());
        return new HikariDataSource(config);
    }
}
