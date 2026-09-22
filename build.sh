#!/bin/bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/albertodavila/peloton-to-garmin/grupetto
./gradlew assembleDebug
