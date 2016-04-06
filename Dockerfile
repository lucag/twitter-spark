# lucag/twitter-spark
#
# VERSION 0.2.2

FROM phusion/baseimage

MAINTAINER Luca Gilardi <lucag@icsi.berkeley.edu>

USER root

# Update OS
#RUN apt-get update && apt-get upgrade -y -o Dpkg::Options::="--force-confold" --fix-missing

# Java 8
RUN echo debconf shared/accepted-oracle-license-v1-1 select true \
    | debconf-set-selections
RUN echo debconf shared/accepted-oracle-license-v1-1 seen true \
    | debconf-set-selections

RUN apt-add-repository -y ppa:webupd8team/java
RUN apt-get -y update
RUN apt-get -y install oracle-java8-installer

# Spark: support for Hadoop 2.6.0
ENV SPARK_HOME      /usr/local/spark
ENV PATH            $SPARK_HOME/bin:$PATH
ENV SPARK_HOME      /usr/local/spark
ENV SPARK_OPTS      --driver-java-options=-Xms1G \
                    --driver-java-options=-Xmx4G \
                    --driver-java-options=-Dlog4j.logLevel=WARN'

RUN curl -s http://d3kbcqa49mib13.cloudfront.net/spark-1.6.0-bin-hadoop2.6.tgz | tar -xz -C /usr/local/
RUN cd /usr/local && ln -s spark-1.6.0-bin-hadoop2.6 spark

# SBT
#RUN echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
#RUN sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 642AC823
#RUN sudo apt-get -y update
#RUN sudo apt-get -y install sbt

RUN apt-get -y update

# Pip
RUN apt-get -y install python-pip

# Jupyter
RUN apt-get -y install build-essential libtool autoconf
RUN apt-get -y install automake pkg-config python-dev
RUN apt-get -y install python-zmq
RUN apt-get -y install python-matplotlib
RUN apt-get -y install python-pandas

RUN apt-get -y install librdkafka-dev
RUN pip install kafka-python
RUN pip install pykafka

RUN pip install jupyter
RUN pip install jupyter_declarativewidgets
RUN pip install jupyter_dashboards

RUN apt-get -y install nodejs
RUN ln -s /usr/bin/nodejs /usr/bin/node
RUN apt-get -y install npm
RUN npm install -g bower

EXPOSE 8888

#
#   Zookeeper
#
ENV HOSTS               localhost
ENV ZK_SERVER_ID        1
ENV ZK_CLIENT_PORT      2181
ENV ZK_PEER_PORT        2888
ENV ZK_ELECTION_PORT    3888

RUN apt-get -y install zookeeperd

ADD etc/zoo.cfg         /etc/zookeeper/conf/zoo.cfg

ADD etc/setup.sh        /tmp/
RUN /tmp/setup.sh

ADD etc/zookeeper.sh    /etc/service/zookeeper/run

# Just in case...
EXPOSE 2181 2888 3888

#
#   Kafka
#
RUN useradd -m kafka
RUN adduser kafka sudo

WORKDIR /home/kafka

RUN wget http://www.us.apache.org/dist/kafka/0.8.2.2/kafka_2.9.2-0.8.2.2.tgz
RUN tar xzf kafka_2.9.2-0.8.2.2.tgz --strip 1
RUN rm      kafka_2.9.2-0.8.2.2.tgz

RUN mkdir /etc/service/kafka
ADD etc/kafka.sh /etc/service/kafka/run

USER root

# Clean up APT when done.
RUN apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Start init...
CMD ["/sbin/my_init"]

#
# Local build
#

ENV APP_USER        lucag
ENV APP_HOME        /home/lucag/twitter-spark
ENV APP_IN_DOCKER   True

USER root

RUN useradd -m -s /bin/bash $APP_USER

COPY var/               $APP_HOME/var/
COPY bin/               $APP_HOME/bin/
COPY notebooks/*.ipynb  $APP_HOME/notebooks/
COPY data/airline-twitter-sentiment/airline-handles $APP_HOME/data/airline-twitter-sentiment/

RUN mkdir $APP_HOME/var/log/

COPY ./target/scala-2.10/TwitterSentimentAnalyzer-assembly-0.2.2.jar $APP_HOME

RUN chown -R lucag.lucag $APP_HOME

# Start Jupyter Notebook
RUN mkdir /etc/service/notebook
ADD etc/notebook.sh /etc/service/notebook/run
##CMD ["jupyter-notebook", "--no-browser", "--notebook-dir=notebooks", "--ip=0.0.0.0"]

#USER lucag
#RUN npm install -g bower

# Start Spark App
RUN mkdir /etc/service/twitter-spark
ADD etc/twitter-spark.sh /etc/service/twitter-spark/run
##CMD sh "${APP_HOME}/bin/run"

USER $APP_USER

# Declarative widgets
RUN jupyter declarativewidgets install --user --symlink --overwrite
RUN jupyter declarativewidgets activate

# Dashboards
RUN jupyter dashboards install --user --symlink --overwrite
RUN jupyter dashboards activate

USER root