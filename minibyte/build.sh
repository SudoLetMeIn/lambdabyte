#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
scalac -encoding UTF-8 -deprecation -feature -unchecked -d build/classes src/*.scala
printf '%s\n' 'Compiled. Run: scala -cp build/classes MiniByte 6 + 7'
