#!/usr/bin/env bash
exec /sbin/setuser ${APP_USER} \
    ${APP_HOME}/bin/run >> ${APP_HOME}/var/log/twiter-spark.log 2>&1

