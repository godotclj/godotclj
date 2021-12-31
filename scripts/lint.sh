#!/usr/bin/env bash
set -euxo pipefail

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
clj-kondo --lint "$DIR/../src/clojure"
clj-kondo --lint "$DIR/../test"
