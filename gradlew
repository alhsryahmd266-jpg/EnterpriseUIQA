#!/usr/bin/env sh
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME="`pwd -P`"
MAX_FD="maximum"
warn() { echo "$*"; }
die() { echo; echo "$*"; echo; exit 1; }
case "`uname`" in CYGWIN*) cygwin=true;; Darwin*) darwin=true;; MSYS*|MINGW*) msys=true;; esac
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD="$JAVA_HOME/jre/sh/java"
  else JAVACMD="$JAVA_HOME/bin/java"; fi
else JAVACMD="java"; fi
if ! command -v "$JAVACMD" > /dev/null 2>&1; then die "ERROR: JAVA_HOME is not set"; fi
eval set -- "$DEFAULT_JVM_OPTS" '"$JAVA_OPTS"' '"$GRADLE_OPTS"' '"-Dorg.gradle.appname=$APP_BASE_NAME"' -classpath '"$CLASSPATH"' org.gradle.wrapper.GradleWrapperMain "$@"
exec "$JAVACMD" "$@"

