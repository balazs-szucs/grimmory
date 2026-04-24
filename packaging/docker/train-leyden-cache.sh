#!/bin/sh
set -eu

DB_DIR=/tmp/leyden-mariadb
DB_SOCKET=/tmp/leyden-mariadb.sock
DB_PID_FILE=/tmp/leyden-mariadb.pid
DB_LOG=/tmp/leyden-mariadb.log
AOT_CACHE_PATH="${AOT_CACHE_PATH:-/app/grimmory.aot}"

cleanup() {
    if [ -f "$DB_PID_FILE" ] && kill -0 "$(cat "$DB_PID_FILE")" 2>/dev/null; then
        mariadb-admin --protocol=socket --socket="$DB_SOCKET" -uroot shutdown >/dev/null 2>&1 || kill "$(cat "$DB_PID_FILE")" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT

rm -rf "$DB_DIR"
mkdir -p "$DB_DIR" /run/mysqld /app/data /bookdrop /books
chown -R mysql:mysql "$DB_DIR" /run/mysqld

mariadb-install-db --user=mysql --datadir="$DB_DIR" >/tmp/leyden-mariadb-install.log 2>&1

mariadbd \
    --user=mysql \
    --datadir="$DB_DIR" \
    --socket="$DB_SOCKET" \
    --pid-file="$DB_PID_FILE" \
    --bind-address=127.0.0.1 \
    --port=3306 \
    --skip-networking=0 \
    --log-error="$DB_LOG" &

attempt=0
until mariadb-admin --protocol=socket --socket="$DB_SOCKET" -uroot ping >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        cat "$DB_LOG" >&2 || true
        exit 1
    fi
    sleep 1
done

mariadb --protocol=socket --socket="$DB_SOCKET" -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS grimmory;
CREATE USER IF NOT EXISTS 'grimmory'@'127.0.0.1' IDENTIFIED BY 'grimmory';
CREATE USER IF NOT EXISTS 'grimmory'@'localhost' IDENTIFIED BY 'grimmory';
GRANT ALL PRIVILEGES ON grimmory.* TO 'grimmory'@'127.0.0.1';
GRANT ALL PRIVILEGES ON grimmory.* TO 'grimmory'@'localhost';
FLUSH PRIVILEGES;
SQL

set +e
APP_LEYDEN_TRAINING_ENABLED=true \
DATABASE_URL="jdbc:mariadb://127.0.0.1:3306/grimmory?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" \
DATABASE_USERNAME=grimmory \
DATABASE_PASSWORD=grimmory \
BOOKLORE_PORT=6060 \
java @/app/grimmory.jvm.args -XX:+AOTClassLinking -XX:AOTCacheOutput="$AOT_CACHE_PATH" -jar /app/app.jar
app_status=$?
set -e

if [ "$app_status" -ne 0 ]; then
    exit "$app_status"
fi

if [ ! -s "$AOT_CACHE_PATH" ]; then
    echo "Leyden AOT cache was not created at $AOT_CACHE_PATH" >&2
    exit 1
fi

ls -lh "$AOT_CACHE_PATH"