# lucag/twitter-spark
#
# VERSION 0.2.0

FROM jupyter/all-spark-notebook

MAINTAINER Luca Gilardi <lucag@icsi.berkeley.edu>

ENV APP_USER        lucag
ENV APP_HOME        /home/lucag/twitter-spark
ENV APP_IN_DOCKER   True
ENV PATH            $SPARK_HOME/bin:$PATH
ENV SPARK_HOME      /usr/local/spark
ENV SPARK_OPTS      --driver-java-options=-Xms1G --driver-java-options=-Xmx4G --driver-java-options=-Dlog4j.logLevel=WARN'

USER root

RUN useradd -m -s /bin/bash $APP_USER

COPY var/  $APP_HOME/var/
COPY bin/  $APP_HOME/bin/
COPY data/airline-twitter-sentiment/airline-handles $APP_HOME/data/airline-twitter-sentiment/

COPY ./target/scala-2.10/TwitterSentimentAnalyzer-assembly-0.2.0.jar $APP_HOME

RUN chown -R lucag.lucag $APP_HOME

USER $APP_USER

RUN cd $APP_HOME

CMD ["bash", "-c", "$APP_HOME/bin/run"]
#ENTRYPOINT ["bash"]

