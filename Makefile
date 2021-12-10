RUNTIME ?= jvm
PROJECT_DIR ?= $(PWD)
GODOT_CLJ_DIR ?= $(CURDIR)
GODOT_HEADERS ?= $(PROJECT_DIR)/godot-headers
BUILD ?= $(PWD)/build
BIN ?= $(PWD)/bin
CLASSES ?= $(PWD)/classes

export CLASSES

CLASSPATH ?= $(shell clj -Spath | clj -M -e "(require 'godotclj.paths)")

CLJ=clj -Scp $(CLASSPATH)

ifeq ($(RUNTIME),graalvm)
JAVA_HOME=$(GRAALVM_HOME)
CFLAGS=-D RUNTIME_GRAALVM=1
else
JAVA_HOME=$(shell clj -M -e "(println (System/getProperty \"java.home\"))")
endif

JAVA_PATH=$(JAVA_HOME)/bin:$(PATH)
JAVAC=$(JAVA_HOME)/bin/javac
NATIVEIMAGE=$(JAVA_HOME)/bin/native-image
GU=$(JAVA_HOME)/bin/gu
CFLAGS += -I$(GODOT_HEADERS)
CFLAGS += -I$(BUILD)
CFLAGS += -I$(GODOT_CLJ_DIR)/src/c
CFLAGS += -I$(BUILD)
CFLAGS += -ggdb

export CFLAGS

ALL=src/clojure/godotclj/api/gdscript.clj $(BIN)/libgodotclj.so $(BIN)/libgodotclj_gdnative.so $(BIN)/libwrapper.so $(BUILD)/wrapper.txt $(BUILD)/wrapper.json $(BUILD)/callback.txt $(BUILD)/callback.json
ifeq ($(RUNTIME),jvm)
ALL += $(CLASSES)/godotclj/loader.class
endif

UBERJAR_DEPS=
CALLBACK_DEPS=

ifeq ($(RUNTIME),graalvm)
UBERJAR_DEPS=$(CLASSES)/godotclj/main_graalvm.class
CALLBACK_DEPS=$(BUILD)/graal_isolate.h
endif

LAYOUTS=$(BUILD)/wrapper.txt $(BUILD)/wrapper.json $(BUILD)/callback.txt $(BUILD)/callback.json $(BUILD)/godot_bindings.json $(BUILD)/godot_bindings.txt

all: $(ALL)

.PHONY: clean
clean:
	rm -fr $(BIN) $(BUILD) $(CLASSES) target build classes src/c/wrapper.h src/c/wrapper.c .cpcache src/clojure/godotclj/api/gdscript.clj

$(BUILD)/%.txt: src/c/%.c
	mkdir -p $(BUILD)
	clang -D RUNTIME_GENERATION=1 $(CFLAGS) -c $< -o $(shell mktemp).o -Xclang -fdump-record-layouts | sed $$'s/\e\\[[0-9;:]*[a-zA-Z]//g' > $@

$(BUILD)/%.json: src/c/%.c
	mkdir -p $(BUILD)
	clang -D RUNTIME_GENERATION=1 $(CFLAGS) -o $(shell mktemp).o -c $< -Xclang -ast-dump=json > $@

# TODO rename wrapper to godot_bindings
src/c/wrapper.h src/c/wrapper.c: $(BUILD)/godot_bindings.txt $(BUILD)/godot_bindings.json
	PATH=$(JAVA_PATH) \
	$(CLJ) -M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (require 'godotclj.gen-wrapper))"

$(BUILD)/%.o: src/c/%.c
	mkdir -p $(BUILD)
	gcc $(CFLAGS) -c $< -o $@ --std=c11 -fPIC -rdynamic

$(BUILD)/libwrapper.so: $(BUILD)/wrapper.o
	mkdir -p $(BUILD)
	gcc $(CFLAGS) -shared -o $(BUILD)/libwrapper.so $(BUILD)/wrapper.o --std=c11 -fPIC -rdynamic

$(CLASSES)/godotclj/context$$Directives.class: src/clojure/godotclj/gen_directives.clj
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
			-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (require 'godotclj.gen-directives))"

src/clojure/godotclj/api/gdscript.clj: src/clojure/godotclj/api/gen_gdscript.clj
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
			-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(require '[godotclj.api.gen-gdscript :refer [gen-api]]) (gen-api)"

$(CLASSES)/godotclj/core/Bindings.class: $(CLASSES)/godotclj/context.class $(BUILD)/wrapper.txt $(BUILD)/wrapper.json $(BUILD)/callback.json $(BUILD)/callback.txt
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
			-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (require 'godotclj.gen-bindings))"

$(CLASSES)/godotclj/main_graalvm.class: src/clojure/godotclj/main_graalvm.clj $(CLASSES)/godotclj/core/Bindings.class $(CLASSES)/godotclj/context.class
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (compile 'godotclj.main-graalvm))"

$(CLASSES)/godotclj/context.class: src/clojure/godotclj/context.clj $(CLASSES)/godotclj/context$$Directives.class
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (compile 'godotclj.context))"

$(CLASSES)/godotclj/loader.class: src/clojure/godotclj/loader.clj
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (compile 'godotclj.loader))"

$(PROJECT_DIR)/target/project.jar: $(PROJECT_DIR)/deps.edn $(UBERJAR_DEPS) $(CLASSES)/godotclj/core/Bindings.class $(CLASSES)/godotclj/context.class $(BUILD)/wrapper.json $(BUILD)/wrapper.txt
	# TODO fix basename (creating target/project)
	mkdir -p $(basename $@)
	mkdir -p $(CLASSES)
	mkdir -p $(BUILD)
	PATH=$(JAVA_PATH) \
	clj -Sdeps '{:deps {uberdeps {:mvn/version "RELEASE"}}}' -M -m uberdeps.uberjar --deps-file $(PROJECT_DIR)/deps.edn --target $@ --main-class godotclj.main_graalvm

ifeq ($(RUNTIME),graalvm)
$(BUILD)/libgodotclj.so $(BUILD)/graal_isolate.h $(BUILD)/libgodotclj.h: $(PROJECT_DIR)/target/project.jar $(BUILD)/libwrapper.so
	PATH=$(JAVA_PATH) \
	cd $(BUILD) && $(GU) install native-image && $(NATIVEIMAGE) -H:+ReportExceptionStackTraces \
	  --native-compiler-options="-I$(GODOT_HEADERS)" \
	  --native-compiler-options="-I$(GODOT_CLJ_DIR)/src/c" \
	  --native-compiler-options="-L$(BUILD)" \
	  --native-compiler-options="-I$(BUILD)" \
	  --native-compiler-options="-Wl,-rpath='$$ORIGIN'" \
	  --report-unsupported-elements-at-runtime \
	  --initialize-at-build-time \
	  --no-fallback \
	  --no-server \
	  -J-Dclojure.spec.skip-macros=true \
	  -J-Dclojure.compiler.direct-linking=true \
	  -J-Dtech.v3.datatype.graal-native=true \
	  --allow-incomplete-classpath \
	  -jar $(PROJECT_DIR)/target/project.jar \
	  --shared \
	  --verbose \
	  --native-image-info \
	  libgodotclj
endif

src/c/callback.o: src/c/callback.c src/c/callback.h $(CALLBACK_DEPS)
src/c/jvm.o: src/c/jvm.cpp src/c/jvm.h
src/c/gdnative.o: src/c/gdnative.c $(BUILD)/libgodotclj.h

$(BUILD)/jvm.o: src/c/jvm.cpp
	mkdir -p $(BUILD)
	g++ $(CFLAGS) -fPIC  -o $(BUILD)/jvm.o -c src/c/jvm.cpp -I $(JAVA_HOME)/include -I $(JAVA_HOME)/include/linux -rdynamic -shared -lpthread

ifeq ($(RUNTIME),graalvm)
$(BUILD)/libgodotclj_gdnative.so: $(BIN)/libgodotclj.so $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/godot_bindings.o $(BUILD)/libwrapper.so
	gcc $(CFLAGS) -Wl,-rpath='$$ORIGIN' -shared -o $@ -I$(BUILD) -I$(GODOT_HEADERS) -Isrc/c --std=c11 -fPIC -rdynamic -L$(BUILD) -lgodotclj $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/godot_bindings.o -lwrapper -L$(BUILD)
else
$(BUILD)/libgodotclj_gdnative.so: $(BUILD)/jvm.o $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/godot_bindings.o $(BUILD)/libwrapper.so
	gcc $(CFLAGS) -Wl,--no-as-needed -Wl,-rpath='$$ORIGIN' -shared -o $@ -I$(BUILD) -I$(GODOT_HEADERS) -Isrc/c --std=c11 -fPIC -rdynamic -L$(BUILD) $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/godot_bindings.o $(BUILD)/jvm.o -I $(JAVA_HOME)/include -I $(JAVA_HOME)/include/linux -ljava -ljvm -L $(JAVA_HOME)/lib -L $(JAVA_HOME)/lib/server -L /usr/lib64 -lwrapper -L$(BUILD)
endif

ifeq ($(RUNTIME),graalvm)
$(BIN)/libgodotclj_gdnative.so: $(BIN)/libgodotclj.so $(BIN)/libwrapper.so $(CLASSES)/godotclj/loader.class $(LAYOUTS)
else
$(BIN)/libgodotclj_gdnative.so: $(BIN)/libwrapper.so $(CLASSES)/godotclj/loader.class $(LAYOUTS)
endif

$(BIN)/%.so: $(BUILD)/%.so
	mkdir -p $(BIN)
	cp $< $@
