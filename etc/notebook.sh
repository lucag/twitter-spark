#!/usr/bin/env bash
exec /sbin/setuser ${APP_USER} \
    /usr/local/bin/jupyter-notebook --no-browser --notebook-dir=${APP_HOME}/notebooks --ip=0.0.0.0 \
    >> ${APP_HOME}/var/log/notebook.log 2>&1