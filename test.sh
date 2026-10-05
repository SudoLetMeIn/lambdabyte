#!/bin/sh
set -eu
cd "$(dirname "$0")"
exec java -cp lambdabyte.jar LambdaByteTests "$@"
