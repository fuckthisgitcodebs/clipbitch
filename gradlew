#!/bin/sh
​Standard Gradle wrapper script for Unix-like systems
​Optimized for Termux execution
​DIR=$(dirname "$0")
[ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ] || { echo "Error: Wrapper JAR missing"; exit 1; }
​exec java -jar "DIR/gradle/wrapper/gradle-wrapper.jar" "@"
