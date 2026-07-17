#!/bin/sh

BASEDIR=$(dirname "$0")
java -jar "$BASEDIR/target/kstars-cluster-1.0-SNAPSHOT.jar" $@
