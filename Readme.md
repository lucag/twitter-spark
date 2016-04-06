# Task: Spark-based Twitter collection and Analysis

## Overview

This is the code that implements the required task. Namely, it selects English-language Tweeter texts mentioning a number of airlines, and, for each 5-minute window, outputs the top counts of user mentions (that it, the airline the text is directed to), together with an analysis of each text received in the current 5-minute window.

The airlines considered are the following:

* Alaska Airlines,  @AlaskaAir
* American Airlines, @AmericanAir
* British Airways, @British_Airways
* Delta Air Lines, @DeltaAssist
* Frontier Airlines, @FlyFrontier
* Hawaiian, @HawaiianAir
* KLM, @KLM
* Lufthansa, @lufthansa
* Lufthansa USA, @Lufthansa_USA
* Quantas, @Qantas
* Singapore Airlines, @SingaporeAir
* Southwest, @Southwestair
* Spirit, @SpiritAirlines
* United, @United
* US Airways, @USAirways
* Virgin America, @VirginAmerica
* Virgin Atlantic, @VirginAtlantic
* Virgin Australia, @VirginAustralia
* Air New Zealand, @FlyAirNZ
* Air New Zealand USA, @AIRNZUSA

The output is saved to a file — please see below for more information.

Here how the top user mention output looks like: 

```
Top 10 handles in the last 300000 ms (non-cumulative):
  11 from @AmericanAir
  8 from @DeltaAssist
  7 from @Qantas
  6 from @SouthwestAir
  3 from @united
  2 from @SingaporeAir
  1 from @FlyFrontier
  1 from @VirginAustralia
```

An example of the sentiment analysis follows below. The tweets are grouped by predicted polarity: `(+)` for positive, `(-)` for negative, `(=)` for neutral. Between square brackets is the actual output of the analyzer, averaged over the sentences composing the tweet. Instead ot taking the average, a different strategy would be to pick the most extreme. The neutral label here is assigned (quite arbitrarily) to a small interval around 2.0, so arguably the most reliable labels are the other two.

The sentiment analyszer uses a Recursive Neural Tensor Network that learns semantics over parse trees, instead of bag of words. It produces state-of-the-art accuracy results.

The code is mostly in Scala, with some Python to evaluate the sentiment analysis.  

For details about the sentiment analysis and an evaluation on this kind of data, please see [the notebook](https://github.com/lucag/twitter-spark/blob/master/notebooks/Accuracy%20Testing.ipynb).

```
Tweets with sentiment in the last 300000 ms:
  (+)
    [3.0]: @SouthwestAir loved the new #Boeing737, beautiful cabin and great crew! DEN-AUS #1262 tha
nks for a great flight! https://t.co/4Rsn2AKlpG
    [3.0]: @MichaelReid90: Arrived at the airport after a great win for @FMoverley! thanks @SQUAS
HTRAVEL for the @AmericanAir flight :) https://t.c…
    [3.0]: Enter to win a trip of a lifetime to New Zealand from @PureNewZealand @FlyAirNZ & @Tumitr
avel via @HarperTravel https://t.co/RtBpPinP85
    [2.5]: Take the @HawaiianAir Love at First Flight for a chance to win a romantic getaway in Hawa
ii! https://t.co/dRX5VLxAFC
    [2.5]: Let’s hear it for @SouthwestAir The twisted/bent suitcase issue was addressed and resolve
d. Great customer service!
  (=)
    [2.0]: Having great luck seeing all the awesome mountains today! Mt St Helens and Mt Adams @Alas
kaAir #iFlyAlaska https://t.co/sJtZzKfLed
    [2.0]: @AmericanAir what time is flight 656 arriving in phoenix
    [2.0]: @RestingPlatypus @DeltaAssist @ladygaga @Beyonce how dare you do this to my bb
    [2.0]: They are helping us #changetheratio! https://t.co/TnRsdtgUVv
    [2.0]: Joe Montana was on my @SouthwestAir flight in 2010 #MVP @JoeMontana https://t.co/xLRDVROSXa
    [2.0]: @AmericanAir Am I able to use MY AAdvantage number for tickets that I purchased for family members?
    [2.0]: Wow! No need for those rubbish cgi videos - I keep playing it over and over! https://t.co/MKBjrWYg40
  (-)
    [1.5]: @AmericanAir I really feel the love with an autobot response and make me jump through hoops to get my money back. #youstink #neveragain
    [1.5]: @British_Airways I am trying to check in for BA 2036 departing MCO Monday Feb 8th but it is not allowing me to. Is there any reason?
https://t.co/iXcyUvn0RG https://t.co/AhKQt63r1j
    [1.0]: Thanks @VirginAmerica for turning on the security video RIGHT as the coin was tossed.
<U+1F621>
    [1.0]: @AmericanAir too long of a layover for the dates I'm looking to fly to Curaçao. I will check back in the hopes for more connecting flights.
    [1.0]: @FionaLakeAus @milkmaidmarian @Cam_Mcilveen @Qantas  is now talking to the video producers  to pull the quad part out as there is no helmet.
    [1.0]: Thank the #upgrade fairies & #SuperBowl @heidischoeneck @AmericanAir @cindygallop @3PercentConf
    [1.0]: @AmericanAir ur not, I was on the phone for 3 hours with very rude people in fact who insisted on simply reading what's in ur policy
    [1.0]: Sometimes you poorly coordinate your travel and the Super Bowl. And you have the one @United… https://t.co/su94REBtUM```
```


## How to run locally

The code assumes a Spark 1.6.0 installation be already present, and Oracle JDK 1.8.

From the command shell (assuming a Linux or OS X environment), clone this repository:

```bash
$ git clone https://github.com/lucag/twitter-spark.git
```

Change into the newly-created `twitter-spark` directory, and type 

```bash
$ ./bin/run
```

To stop the computation, simply hit `Ctrl-C`. The output is created in the file `var/report.txt`; it 
can be looked at as follows:

```bash
$ tail -f var/report.txt
```

Such file is also [accessible from the repository itself](https://raw.githubusercontent.com/lucag/twitter-spark/master/var/report.txt).
 
The output is created according to requirements, i.e., every 5 minutes. Such time is wired in the code (in the file `src/main/scala/BasicTask.scala` around  line 185).

To recompile, issue:

```bash
$ sbt assembly
```

followed again by:

```bash
$ ./bin/run
```

## Docker

Docker support is now full: testing has been successfully completed on an container with 8GB or RAM. It's been pushed it to Docker Hub. To run it, enter: 

```bash
$ docker run -it --name twitter_run lucag/twitter-spark
```

In order to see the report file, one has to log into the running container:

```bash
$ docker exec -it twitter_run bash
```

and then look at the `report.txt` file:

```bash
$ tail -F $APP_HOME/var/report.txt
```

To rebuild the container, issue:  
 
```bash
$ docker build -t lucag/twitter-spark .
```

And, as above, to run it under the name "arbitrary_name" (the same name to be used with `exec`), issue:

```bash
$ docker run -it --name arbitrary_name lucag/twitter-spark
```




