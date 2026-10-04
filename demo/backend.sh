#!/usr/bin/env bash
# Day 13 — the orders-backend the Order Detail panel reads, on :8082, in the background.
#
#   bash demo/backend.sh start | stop | restart | status
#
# It serves whatever branch is checked out: main during Slide 4, the Money refactor after
# Slide 3's migration. The jar is rebuilt only when a source file is newer than it, so a
# restart right after slide3-break.sh (whose mvn verify already built it) takes seconds.
#
# Why not :8080 — mvn verify starts its OWN copy of orders-backend on :8080 to write the
# spec. If something else already listens there, the springdoc plugin downloads the spec from
# that other app instead, the "candidate" spec is the old contract, and the compile break
# silently never happens. That is a demo that fails on stage with no error message.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need java mvn curl lsof

BACKEND="$ROOT/orders-backend"
JAR="$BACKEND/target/orders-backend-0.0.1-SNAPSHOT.jar"
# The running JVM gets its OWN copy. slide3-break.sh's mvn verify rewrites target/*.jar while
# this backend is serving; a JVM whose jar changes underneath it can fail on the next class load.
RUN_JAR="$STATE_DIR/orders-backend-display.jar"
PID_FILE="$STATE_DIR/backend.pid"
LOG_FILE="$STATE_DIR/backend.log"
URL="http://localhost:$DISPLAY_PORT/api/orders/1"

build_if_stale() {
  if [ -f "$JAR" ] && [ -z "$(find "$BACKEND/src/main" "$BACKEND/pom.xml" -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
    echo "jar is current for branch $(current_branch)"
    return
  fi
  echo "building orders-backend jar for branch $(current_branch) (tests skipped) ..."
  (cd "$BACKEND" && mvn -q -DskipTests package)
}

stop() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    kill "$(cat "$PID_FILE")"
    local i=0
    while kill -0 "$(cat "$PID_FILE")" 2>/dev/null && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
    echo "stopped display backend (pid $(cat "$PID_FILE"))"
  fi
  rm -f "$PID_FILE"
  local other
  other="$(port_pid "$DISPLAY_PORT")"
  [ -z "$other" ] || die "port $DISPLAY_PORT is still held by pid $other, which this script did not start. Stop it yourself (kill $other)."
}

start() {
  local other
  other="$(port_pid "$DISPLAY_PORT")"
  [ -z "$other" ] || die "port $DISPLAY_PORT is already in use by pid $other. Run: bash demo/backend.sh stop"
  build_if_stale
  cp "$JAR" "$RUN_JAR"
  nohup java -jar "$RUN_JAR" --server.port="$DISPLAY_PORT" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  local i=0
  while [ "$(http_code "$URL" 2)" != "200" ]; do
    i=$((i + 1))
    if [ $i -ge 60 ] || ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      tail -20 "$LOG_FILE" >&2
      die "orders-backend did not answer on :$DISPLAY_PORT (log: $LOG_FILE)"
    fi
    sleep 1
  done
  echo "orders-backend up on :$DISPLAY_PORT (pid $(cat "$PID_FILE"), branch $(current_branch))"
  curl -s "$URL"; echo
}

status() {
  if [ "$(http_code "$URL" 2)" = "200" ]; then
    echo "orders-backend answering on :$DISPLAY_PORT (pid $(port_pid "$DISPLAY_PORT"))"
    curl -s "$URL"; echo
  else
    echo "orders-backend NOT answering on :$DISPLAY_PORT"
    return 1
  fi
}

case "${1:-status}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  *) die "usage: bash demo/backend.sh start|stop|restart|status" ;;
esac
