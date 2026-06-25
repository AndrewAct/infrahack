#!/usr/bin/env bash
# Build the module and run the TestNG suite using jars from the local Maven repo.
set -euo pipefail

cd "$(dirname "$0")/.."
M2="${HOME}/.m2/repository"
OUT="out"

CP="$M2/org/testng/testng/7.1.0/testng-7.1.0.jar"
CP="$CP:$M2/com/beust/jcommander/1.72/jcommander-1.72.jar"
CP="$CP:$M2/com/google/inject/guice/4.1.0/guice-4.1.0-no_aop.jar"
CP="$CP:$M2/javax/inject/javax.inject/1/javax.inject-1.jar"
CP="$CP:$M2/aopalliance/aopalliance/1.0/aopalliance-1.0.jar"
CP="$CP:$M2/com/google/guava/guava/19.0/guava-19.0.jar"
CP="$CP:$M2/org/yaml/snakeyaml/1.21/snakeyaml-1.21.jar"

rm -rf "$OUT" && mkdir -p "$OUT"
javac -cp "$CP" -d "$OUT" $(find src -name '*.java')

java -cp "$OUT:$CP" org.testng.TestNG \
  -testclass io.infrahack.parkinglot.test.ParkingServiceTest,io.infrahack.parkinglot.test.PricingTest,io.infrahack.parkinglot.test.ConcurrencyTest \
  -d "$OUT/testng-report"
