#!/usr/bin/env bash
exec /sbin/setuser kafka \
    ~kafka/bin/kafka-server-start.sh ~kafka/config/server.properties \
    >> ${APP_HOME}/var/log/kafka.log 2>&1

