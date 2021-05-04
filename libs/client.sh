#!/bin/sh

BASEDIR=$(dirname "$0")
java -classpath "$BASEDIR/*" de.pmneo.kstars.ClientRunner $@  
