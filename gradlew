#!/bin/sh

APP_BASE_NAME=`basename "$0"`

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

warn ( ) {
    echo "${APP_BASE_NAME}: ${1}" >&2
}

die ( ) {
    echo
    echo "${APP_BASE_NAME}: ${1}" >&2
    echo
    exit 1
}

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

exec java $DEFAULT_JVM_OPTS $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
