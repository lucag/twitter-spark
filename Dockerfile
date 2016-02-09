# lucag/twitter-spark
#
# VERSION 0.2.0

FROM phusion/baseimage

MAINTAINER Luca Gilardi <lucag@icsi.berkeley.edu>

USER root

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

COPY var/  $APP_HOME/var/
COPY bin/  $APP_HOME/bin/
COPY data/airline-twitter-sentiment/airline-handles $APP_HOME/data/airline-twitter-sentiment/

COPY ./target/scala-2.10/TwitterSentimentAnalyzer-assembly-0.2.0.jar $APP_HOME

RUN chown -R lucag.lucag $APP_HOME

USER $APP_USER

RUN cd $APP_HOME

CMD sh "${APP_HOME}/bin/run"

