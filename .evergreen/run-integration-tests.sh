#!/usr/bin/env bash

set -o xtrace   # Write all commands first to stderr
set -o errexit  # Exit the script with error if any of the commands fail

############################################
#            Main Program                  #
############################################

source java-config.sh

echo "mongo-spring-session: running integration tests ..."

echo "MongoDB version: ${MONGODB_VERSION}; topology: ${TOPOLOGY}"

./gradlew -version

if [[ "$TEST_LATEST" == "true" ]]; then
  echo "Testing against the latest version:"
  ./gradlew -q dependencies --configuration compileClasspath -Dlatest=$TEST_LATEST
fi

./gradlew -PjavaVersion=${JAVA_VERSION} --stacktrace --info --continue clean integrationTest -Dlatest=$TEST_LATEST
