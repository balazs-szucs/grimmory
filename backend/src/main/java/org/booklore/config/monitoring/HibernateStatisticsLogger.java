package org.booklore.config.monitoring;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.RequestHandledEvent;

@Component
public class HibernateStatisticsLogger {

    private static final Logger log = LoggerFactory.getLogger(HibernateStatisticsLogger.class);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @EventListener(RequestHandledEvent.class)
    public void logStatistics(RequestHandledEvent event) {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        if (sessionFactory == null) {
            return;
        }

        Statistics stats = sessionFactory.getStatistics();
        if (stats == null) {
            return;
        }

        if (stats.getQueryExecutionCount() > 0) {
            String uri = org.slf4j.MDC.get("uri");
            if (uri == null) {
                uri = event.getDescription();
            }
            log.info("HIBERNATE_STATS | uri={} | queries={} | entitiesLoaded={} | collectionsLoaded={} | slowQueries={}",
                    uri,
                    stats.getQueryExecutionCount(),
                    stats.getEntityLoadCount(),
                    stats.getCollectionLoadCount(),
                    stats.getSlowQueries() != null ? stats.getSlowQueries().size() : 0);

            stats.clear();
        }
    }
}
