#!/usr/bin/env bash

exec /sbin/setuser zookeeper /usr/bin/java -cp \
      /etc/zookeeper/conf:/usr/share/java/jline.jar:/usr/share/java/log4j-1.2.jar:/usr/share/java/xercesImpl.jar:/usr/share/java/xmlParserAPIs.jar:/usr/share/java/netty.jar:/usr/share/java/slf4j-api.jar:/usr/share/java/slf4j-log4j12.jar:/usr/share/java/zookeeper.jar \
      -Dcom.sun.management.jmxremote \
      -Dcom.sun.management.jmxremote.local.only=false \
      -Dzookeeper.root.logger=INFO,CONSOLE \
      org.apache.zookeeper.server.quorum.QuorumPeerMain \
      /etc/zookeeper/conf/zoo.cfg \
      >> /var/log/zookeeper/zookeeper.log
