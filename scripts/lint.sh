#!/usr/bin/env bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
clj-kondo --lint "$DIR/../src/clojure"
