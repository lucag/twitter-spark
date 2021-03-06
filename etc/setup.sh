#!/bin/bash

# default
HOSTS=${HOSTS:-"localhost"}

# read HOSTS into HOSTS_ARRAY
IFS=', ' read -a HOSTS_ARRAY <<< "$HOSTS"

# config paths
ZOOKEEPER_CONF_PATH="/etc/zookeeper/conf"

# data paths
ZOOKEEPER_DATA_PATH=${ZOOKEEPER_DATA_PATH:-"/data"}
ZOOKEEPER_DATA_LOG_DIR=${ZOOKEEPER_DATA_LOG_DIR:-"/data-log"}

mkdir -p $ZOOKEEPER_DATA_PATH
chown -R zookeeper.zookeeper $ZOOKEEPER_DATA_PATH

mkdir -p $ZOOKEEPER_DATA_LOG_DIR
chown -R zookeeper.zookeeper $ZOOKEEPER_DATA_LOG_DIR

#-------zookeeper config----------#

ZK_CLIENT_PORT=${ZK_CLIENT_PORT:-"2181"}
ZK_PEER_PORT=${ZK_PEER_PORT:-"2888"}
ZK_ELECTION_PORT=${ZK_ELECTION_PORT:-"3888"}

echo $ZK_SERVER_ID > $ZOOKEEPER_CONF_PATH/myid
cp $ZOOKEEPER_CONF_PATH/myid $ZOOKEEPER_DATA_PATH/myid

echo "dataDir=$ZOOKEEPER_DATA_PATH" >> $ZOOKEEPER_CONF_PATH/zoo.cfg
echo "dataLogDir=$ZOOKEEPER_DATA_LOG_DIR" >> $ZOOKEEPER_CONF_PATH/zoo.cfg

echo "clientPort=$ZK_CLIENT_PORT" >> $ZOOKEEPER_CONF_PATH/zoo.cfg
for i in "${!HOSTS_ARRAY[@]}"; do 
  echo "server.$(($i+1))=${HOSTS_ARRAY[$i]}:$ZK_PEER_PORT:$ZK_ELECTION_PORT" >> $ZOOKEEPER_CONF_PATH/zoo.cfg
done

