#!/usr/bin/env bash
function paths {
    clj -Spath | clj -M -e "(require 'godotclj.paths)"
}

JAVA_HOME=$(clj -M -e "(println (System/getProperty \"java.home\"))")
export LD_LIBRARY_PATH=$JAVA_HOME/lib:$JAVA_HOME/lib/server:${LD_LIBRARY_PATH}
export CLASSPATH="$(paths)"

exec godot $@
