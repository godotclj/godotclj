#!/usr/bin/env bash
set -euxo pipefail

clojure -A:test -M -m kaocha.runner "$@"
