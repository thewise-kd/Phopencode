#!/bin/sh
# Verifies the Gradle wrapper with sha256sum and nothing from the wrapper itself,
# so it can be trusted before ./gradlew is run for the first time.
#
# Usage: gradle/verify-wrapper.sh [--online]
#   no flag   compares the wrapper jar with the pin committed next to it and
#             checks the shape of gradle-wrapper.properties.
#   --online  also compares both pins with the values Gradle publishes.
#
# gradle-wrapper.properties is not parsed here. java.util.Properties, which the
# wrapper uses, also accepts other separators, line continuations, escapes and
# repeated keys, and takes the last value. So only a form both read the same way is
# accepted, and anything else fails: comments, empty lines and key=value lines with
# known keys, each key at most once.
#
# PHOPENCODE_WRAPPER_DIR overrides the wrapper directory, for tests.
set -eu

# Byte-wise matching: a locale must not change what the patterns accept.
LC_ALL=C
export LC_ALL

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
wrapper_dir=${PHOPENCODE_WRAPPER_DIR:-$root/gradle/wrapper}
properties=$wrapper_dir/gradle-wrapper.properties
jar=$wrapper_dir/gradle-wrapper.jar
jar_pin_file=$wrapper_dir/gradle-wrapper.jar.sha256
official=https://services.gradle.org/distributions
known_keys='distributionBase distributionPath distributionSha256Sum distributionUrl networkTimeout
retries retryBackOffMs validateDistributionUrl zipStoreBase zipStorePath'
required_keys='distributionSha256Sum distributionUrl validateDistributionUrl'

fail() {
  echo "verify-wrapper: $1" >&2
  exit 1
}

key_count() {
  grep -c "^$1=" "$properties" || true
}

property() {
  sed -n "s/^$1=//p" "$properties"
}

# CR and form feed end a line or count as whitespace for Properties but not for
# grep, so a line that looks like a comment here could hold a key there.
check_bytes() {
  [ "$(tr -d '\000\r\f' <"$properties" | sha256sum)" = "$(sha256sum <"$properties")" ] ||
    fail "gradle-wrapper.properties holds a NUL, CR or form feed"
}

# A value holds no whitespace and no backslash, so it has no continuation or escape.
# The one exception is the https\:// that Gradle itself writes.
check_lines() {
  keys=$(printf '%s' "$known_keys" | tr ' \n' '||')
  allowed="^\$|^[#!]|^($keys)=[^\\\\[:space:]]*\$|^distributionUrl=https\\\\://[^\\\\[:space:]]*\$"
  rejected=$(grep -nEv "$allowed" "$properties" || true)
  [ -z "$rejected" ] || fail "gradle-wrapper.properties holds a line this script does not accept: $rejected"
}

check_keys() {
  for key in $known_keys; do
    [ "$(key_count "$key")" -le 1 ] || fail "$key is repeated"
  done
  for key in $required_keys; do
    [ "$(key_count "$key")" -eq 1 ] || fail "$key is missing"
  done
}

is_sha256() {
  printf '%s' "$1" | grep -Eq '^[0-9a-f]{64}$'
}

[ -f "$properties" ] || fail "missing $properties"
[ -f "$jar" ] || fail "missing $jar"
[ -f "$jar_pin_file" ] || fail "missing $jar_pin_file"

check_bytes
check_lines
check_keys

url=$(property distributionUrl)
distribution_sum=$(property distributionSha256Sum)
version=$(printf '%s' "$url" |
  sed -n 's|^https\\\{0,1\}://services\.gradle\.org/distributions/gradle-\([0-9]\{1,\}\(\.[0-9]\{1,\}\)*\)-bin\.zip$|\1|p')
jar_pin=$(tr -d '[:space:]' <"$jar_pin_file")
jar_sum=$(sha256sum "$jar" | cut -d' ' -f1)

[ -n "$version" ] || fail "distributionUrl is not an official -bin.zip URL: $url"
is_sha256 "$distribution_sum" || fail "distributionSha256Sum is missing or is not a SHA-256"
[ "$(property validateDistributionUrl)" = true ] || fail "validateDistributionUrl must be true"
is_sha256 "$jar_pin" || fail "the pin in $jar_pin_file is not a SHA-256"
[ "$jar_sum" = "$jar_pin" ] || fail "gradle-wrapper.jar hash $jar_sum does not match the pin $jar_pin"

if [ "${1:-}" = --online ]; then
  published_distribution=$(curl -fsSL "$official/gradle-$version-bin.zip.sha256")
  published_jar=$(curl -fsSL "$official/gradle-$version-wrapper.jar.sha256")
  [ "$distribution_sum" = "$published_distribution" ] ||
    fail "distributionSha256Sum differs from the published $published_distribution"
  [ "$jar_sum" = "$published_jar" ] ||
    fail "gradle-wrapper.jar hash differs from the published $published_jar"
  echo "verify-wrapper: OK, Gradle $version, both hashes match the published values"
else
  echo "verify-wrapper: OK, Gradle $version, jar matches the pin"
fi
