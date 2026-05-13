#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# OS specific support.
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in
  CYGWIN* )         cygwin=true  ;;
  Darwin* )         darwin=true  ;;
  MSYS* | MINGW* )  msys=true    ;;
  NONSTOP* )        nonstop=true ;;
esac

JAVA_HOME_VAR="${JAVA_HOME:-}"

if [ -z "$JAVA_HOME_VAR" ] ; then
    if [ -x "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" ] ; then
        JAVA_HOME_VAR="/usr/lib/jvm/java-17-openjdk-amd64"
    fi
fi

if [ -n "$JAVA_HOME_VAR" ] ; then
    if [ -x "$JAVA_HOME_VAR/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME_VAR/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME_VAR/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        JAVACMD=java
    fi
else
    JAVACMD=java
fi

GRADLE_OPTS="${GRADLE_OPTS:-}"
JAVA_OPTS="${JAVA_OPTS:-}"

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
