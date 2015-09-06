#!/bin/bash

# This script installs the spatialhadoop package (in this case spatialhadoop-2.4 onto a Hortonworks HDP cluster)

HDP_VERSION="2.2.6.0-2800"
SPATIALHADOOP_VERSION="spatialhadoop-2.4-rc1-2"

mkdir spatialhadoop2
cd spatialhadoop2
wget https://storage.googleapis.com/osm_hadoop/$SPATIALHADOOP_VERSION.tar.gz
tar -xzvf $SPATIALHADOOP_VERSION.tar.gz

sudo cp bin/* /usr/bin 
sudo cp etc/hadoop/conf/* /etc/hadoop/conf
sudo cp -r share/hadoop/common/lib/* /usr/local/lib/hadoop/lib
sudo cp share/hadoop/common/lib/$SPATIALHADOOP_VERSION /usr/hdp/$HDP_VERSION/hadoop/lib/
sudo cp spatialhadoop-main.jar /usr/hdp/$HDP_VERSION/hadoop-mapreduce