# Ultimate Query Monitoring & Profiling Guide

This guide details the complete query monitoring infrastructure implemented in the backend, how to read the newly exposed metrics, and how to analyze database logs to detect N+1 query patterns, slow queries, and connection pool saturation.

---

## 🚀 1. The Implemented Monitoring Infrastructure

We have enabled a complete, multi-tiered query monitoring setup:

```
[ HTTP Request ] ────> [ QueryMonitoringFilter ] MDC / Correlation ID start
                             │
                             ├───> ThreadLocal start counting SQL queries
                             │
                             v
                       [ JPA / Hibernate ] ───> [ QueryCountInterceptor ] (StatementInspector)
                             │
                             v
                       [ Spring MVC Event ] ──> [ HibernateStatisticsLogger ] (RequestHandledEvent)
                             │
                             v
[ MDC Log Output ] <─── [ Filter Completion ] MDC / correlation ID end & structured metrics
```

### New Properties Configured (`application.yaml`):
* **`generate_statistics: true`**: Enables Hibernate internal statistics engine.
* **`log_slow_query: 100`**: Automatically logs SQL statements taking longer than **100ms** under `org.hibernate.SQL_SLOW`.
* **`format_sql: true`**: Formats raw SQL statements to be highly readable.
* **`session_factory.statement_inspector`**: Registers the custom `QueryCountInterceptor` directly with Hibernate to count SQL executions per thread.
* **Actuator + Prometheus Exposure**: Exposes `/actuator/prometheus` to scrape HikariCP, JVM, and Hibernate metrics.

---

## 📊 2. Analyzing the AI-Friendly Log Output

Every web request now outputs structured, highly parseable lines in your log stream.

### Request Completed Log (`REQUEST_COMPLETE`)
Logged automatically when the request leaves the Spring servlet:
```
2026-05-21 15:20:10.123 [tomcat-handler-1] INFO  o.b.c.m.QueryMonitoringFilter - REQUEST_COMPLETE | requestId=a1b2c3d4 | method=GET | uri=/api/v1/books/list | status=200 | durationMs=35 | requestId=a1b2c3d4 | uri=/api/v1/books/list
```
* **Key fields**: `requestId` (correlation UUID), `method`, `uri`, `status`, `durationMs` (total roundtrip execution time).

### Thread Query Count Log (`QUERY_COUNT`)
Counts every query executed on the thread of this request via the `QueryCountInterceptor`:
```
2026-05-21 15:20:10.124 [tomcat-handler-1] INFO  o.b.c.m.QueryMonitoringFilter - QUERY_COUNT | requestId=a1b2c3d4 | uri=/api/v1/books/list | queries=1 | requestId=a1b2c3d4 | uri=/api/v1/books/list
```
* **Key fields**: `queries` (the exact number of SQL select/insert/update statements fired during the request).

### Hibernate Statistics Log (`HIBERNATE_STATS`)
Fires right after Spring handles the request, reading the un-wrapped Hibernate session statistics:
```
2026-05-21 15:20:10.125 [tomcat-handler-1] INFO  o.b.c.m.HibernateStatisticsLogger - HIBERNATE_STATS | uri=/api/v1/books/list | queries=1 | entitiesLoaded=250 | collectionsLoaded=0 | slowQueries=0 | requestId=a1b2c3d4 | uri=/api/v1/books/list
```
* **Key fields**: 
  * `queries`: Hibernate-tracked query count.
  * `entitiesLoaded`: Number of core database entities hydrated.
  * `collectionsLoaded`: Number of lazy/eager one-to-many or many-to-many relationship collections initialized.
  * `slowQueries`: Number of queries exceeding the slow-query threshold.

---

## 🕵️‍♂️ 3. How to Detect N+1 Queries

An N+1 query problem occurs when the application fetches a list of parent entities (1 query) and then fetches the lazy associations for each of the child entities (N queries).

### Identifying N+1 in your Logs:
1. **Look at `QUERY_COUNT`**:
   * If an endpoint returns a list of 50 books, and the log shows `queries=51` or `queries=150`, you have a critical N+1 issue.
   * **Optimized Goal**: Listing endpoints should ideally execute **1 or 2 queries** regardless of catalog size (e.g. `queries=1` or `queries=2`).
2. **Look at `HIBERNATE_STATS`**:
   * If `collectionsLoaded` is extremely high (e.g., `collectionsLoaded=50`), it means Hibernate is triggering individual collection fetches.
   * In a tuned list endpoint using flat projections (`BookListItemDTO`), `collectionsLoaded` will be **`0`** because the relationships are flattened and aggregated in SQL using `STRING_AGG` aggregates!

---

## 🛠️ 4. Profiling Database Queries (PostgreSQL / MariaDB)

If you are running in production or staging, you can profile query performance directly in the database.

### For PostgreSQL (Activating `pg_stat_statements`):
Connect to your database and run:
```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 1. Identify Top 10 Most Expensive Queries (by total time)
SELECT query, calls, total_exec_time / 1000 AS total_sec, mean_exec_time AS avg_ms, rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;

-- 2. Identify Potential N+1 Queries (High execution frequency, low avg time)
SELECT query, calls, mean_exec_time AS avg_ms, rows
FROM pg_stat_statements
WHERE query NOT LIKE '%pg_stat%'
ORDER BY calls DESC
LIMIT 10;
```

### For MariaDB / MySQL (Slow Query Log):
Add the following to your MariaDB config (`my.cnf`) or run as root:
```sql
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.1; -- Log any query taking longer than 100ms
SET GLOBAL log_output = 'TABLE';  -- Write logs to mysql.slow_log table

-- Querying the slow log
SELECT start_time, user_host, query_time, rows_sent, rows_examined, db, sql_text 
FROM mysql.slow_log 
ORDER BY start_time DESC 
LIMIT 20;
```

---

## 📈 5. Monitoring HikariCP Connection Pools via Actuator

Because we added `micrometer-registry-prometheus`, the Actuator metrics expose connection pool health in real-time at `GET /actuator/prometheus`.

Key HikariCP metrics to alert on:
* **`hikaricp_connections_active`**: Connections currently leased by request threads. If this is constantly matching your `maximum-pool-size` (configured as `5`), threads will begin queuing.
* **`hikaricp_connections_pending`**: Number of threads blocked waiting to acquire a connection. If this is `> 0`, you have connection pool starvation (possibly due to holding connections open during heavy non-db operations).
* **`hikaricp_connections_timeout_total`**: The total count of connection timeout failures. Highly critical to monitor.

---

## 📝 6. Summary Checklist for Profiling a New API

1. **Invoke the Endpoint**: e.g., call `GET /api/v1/books/list`.
2. **Examine the correlation logs**:
   ```
   REQUEST_COMPLETE | requestId=7b8c9d0e | durationMs=32
   QUERY_COUNT      | requestId=7b8c9d0e | queries=1
   HIBERNATE_STATS  | requestId=7b8c9d0e | queries=1 | entitiesLoaded=250 | collectionsLoaded=0 | slowQueries=0
   ```
3. **If `queries > 2`**: Examine the printed formatted SQL queries in the console to pinpoint which relationships are loaded lazily.
4. **Use DTO projections or `@EntityGraph`** to optimize the query plan to a single fetch.
