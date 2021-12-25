#!/usr/bin/env bash
function paths {
    clj -Spath | clj -M -e "(require 'godotclj.paths)"
}

function java_home {
    if [ ! -e ".java_home" ]; then
        clj -M -e "(println (System/getProperty \"java.home\"))" | tee .java_home
    else
        cat .java_home
    fi
}

function class_path {
    if [ ! -e ".paths" ]; then
        paths | tee .class_path
    else
        cat .class_path
    fi
}

export JAVA_HOME=${JAVA_HOME:-$(java_home)}
export CLASSPATH=$(class_path)

export LD_LIBRARY_PATH=$JAVA_HOME/lib:$JAVA_HOME/lib/server:${LD_LIBRARY_PATH}

if [ ! -e "natives" ]; then
    clj -M -e "(require 'godotclj.natives) (godotclj.natives/extract-native-libraries)"
fi

exec godot $@
