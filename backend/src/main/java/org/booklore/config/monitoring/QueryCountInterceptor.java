package org.booklore.config.monitoring;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

@Component
public class QueryCountInterceptor implements StatementInspector {

    private static final ThreadLocal<Integer> queryCount = new ThreadLocal<>();

    public static void startCounting() {
        queryCount.set(0);
    }

    public static int getQueryCount() {
        return queryCount.get() != null ? queryCount.get() : 0;
    }

    public static void clear() {
        queryCount.remove();
    }

    @Override
    public String inspect(String sql) {
        Integer count = queryCount.get();
        if (count != null) {
            queryCount.set(count + 1);
        } else {
            queryCount.set(1);
        }
        return sql;
    }
}
