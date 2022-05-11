RUNTIME ?= jvm
PROJECT_DIR ?= $(PWD)
GODOT_CLJ_DIR ?= $(CURDIR)
GODOT_HEADERS ?= $(GODOT_CLJ_DIR)/godot-headers
BUILD ?= $(PWD)/build
GEN ?= $(PWD)/gen
BIN ?= $(PWD)/bin
CLASSES ?= $(PWD)/classes
LIB ?= $(PWD)/lib

export CLASSES

CLJ=clojure

ifeq ($(RUNTIME),graalvm)
JAVA_HOME=$(GRAALVM_HOME)
CFLAGS=-D RUNTIME_GRAALVM=1
else
JAVA_HOME=$(shell clojure -M -e "(println (System/getProperty \"java.home\"))")
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

ALL=src/clojure/godotclj/api/gdscript.clj $(BIN)/libgodotclj_gdnative.so $(BIN)/libwrapper.so $(GEN)/wrapper.txt $(GEN)/wrapper.json $(GEN)/callback.txt $(GEN)/callback.json

ifeq ($(RUNTIME),jvm)
ALL += $(CLASSES)/godotclj/loader.class target/godotclj.jar
endif

UBERJAR_DEPS=
CALLBACK_DEPS=

ifeq ($(RUNTIME),graalvm)
UBERJAR_DEPS=$(CLASSES)/godotclj/main_graalvm.class
CALLBACK_DEPS=$(BUILD)/graal_isolate.h
endif

LAYOUTS=$(GEN)/wrapper.txt $(GEN)/wrapper.json $(GEN)/callback.txt $(GEN)/callback.json $(GEN)/godot_bindings.json $(GEN)/godot_bindings.txt

all: $(ALL)

# Added for automated tests in github actions
.PHONY: gen
gen: src/clojure/godotclj/api/gdscript.clj $(LAYOUTS)

$(GEN)/%.txt: src/c/%.c
	mkdir -p $(shell dirname $@)
	clang -D RUNTIME_GENERATION=1 $(CFLAGS) -c $< -o $(shell mktemp).o -Xclang -fdump-record-layouts | sed $$'s/\e\\[[0-9;:]*[a-zA-Z]//g' > $@

$(GEN)/%.json: src/c/%.c
	mkdir -p $(shell dirname $@)
	clang -D RUNTIME_GENERATION=1 $(CFLAGS) -o $(shell mktemp).o -c $< -Xclang -ast-dump=json > $@

# TODO rename wrapper to godot_bindings
src/c/wrapper.h src/c/wrapper.c: $(GEN)/godot_bindings.txt $(GEN)/godot_bindings.json
	PATH=$(JAVA_PATH) \
	$(CLJ) -M -e "(with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (require 'godotclj.gen-wrapper))"

$(BUILD)/%.o: src/c/%.c
	mkdir -p $(BUILD)
	gcc $(CFLAGS) -c $< -o $@ --std=c11 -fPIC -rdynamic

$(BUILD)/libwrapper.so: $(BUILD)/wrapper.o $(BUILD)/godot_bindings.o
	mkdir -p $(BUILD)
	gcc $(CFLAGS) -shared -o $@ $^ --std=c11 -fPIC -rdynamic

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

$(CLASSES)/godotclj/core/Bindings.class: $(CLASSES)/godotclj/context.class $(GEN)/wrapper.txt $(GEN)/wrapper.json $(GEN)/callback.json $(GEN)/callback.txt
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

$(PROJECT_DIR)/target/project-graalvm.jar: $(PROJECT_DIR)/deps.edn $(UBERJAR_DEPS) $(CLASSES)/godotclj/core/Bindings.class $(CLASSES)/godotclj/context.class $(GEN)/wrapper.json $(GEN)/wrapper.txt
	# TODO fix basename (creating target/project)
	mkdir -p $(shell dirname $@)
	mkdir -p $(CLASSES)
	mkdir -p $(BUILD)
	PATH=$(JAVA_PATH) \
	$(CLJ) -Sdeps '{:deps {uberdeps/uberdeps {:mvn/version "1.1.1"}}}' -M -m uberdeps.uberjar --deps-file $(PROJECT_DIR)/deps.edn --target $@ --main-class godotclj.main_graalvm

ifeq ($(RUNTIME),graalvm)
$(BUILD)/libgodotclj.so $(BUILD)/graal_isolate.h $(BUILD)/libgodotclj.h: $(PROJECT_DIR)/target/project-graalvm.jar $(BUILD)/libwrapper.so
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
	  -jar $(PROJECT_DIR)/target/project-graalvm.jar \
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
$(BUILD)/libgodotclj_gdnative.so: $(BIN)/libgodotclj.so $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/libwrapper.so
	gcc $(CFLAGS) -Wl,-rpath='$$ORIGIN' -shared -o $@ -I$(BUILD) -I$(GODOT_HEADERS) -Isrc/c --std=c11 -fPIC -rdynamic -L$(BUILD) -lgodotclj $(BUILD)/gdnative.o $(BUILD)/callback.o -lwrapper -L$(BUILD)
else
$(BUILD)/libgodotclj_gdnative.so: $(BUILD)/jvm.o $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/libwrapper.so
	gcc $(CFLAGS) -Wl,--no-as-needed -Wl,-rpath='$$ORIGIN' -shared -o $@ -I$(BUILD) -I$(GODOT_HEADERS) -Isrc/c --std=c11 -fPIC -rdynamic -L$(BUILD) $(BUILD)/gdnative.o $(BUILD)/callback.o $(BUILD)/jvm.o -I $(JAVA_HOME)/include -I $(JAVA_HOME)/include/linux -ljava -ljvm -L $(JAVA_HOME)/lib -L $(JAVA_HOME)/lib/server -L /usr/lib64 -lwrapper -L$(BUILD)
endif

ifeq ($(RUNTIME),graalvm)
$(BIN)/libgodotclj_gdnative.so: $(BIN)/libgodotclj.so $(BIN)/libwrapper.so $(CLASSES)/godotclj/loader.class $(LAYOUTS)
else
$(BIN)/libgodotclj_gdnative.so: $(BIN)/libwrapper.so $(CLASSES)/godotclj/loader.class $(LAYOUTS)
endif

$(BIN)/%.so: $(BUILD)/%.so
	mkdir -p $(BIN)
	cp $< $@

$(LIB)/natives/linux_64/libwrapper.so: $(BUILD)/libwrapper.so
	mkdir -p $(shell dirname $@)
	cp $< $@

$(LIB)/natives/linux_64/libgodotclj_gdnative.so: $(BUILD)/libgodotclj_gdnative.so
	mkdir -p $(shell dirname $@)
	cp $< $@

$(LIB)/godot-headers/api.json: godot-headers/api.json
	mkdir -p $(shell dirname $@)
	cp $< $@

$(CLASSES)/godotclj/api/gdscript.class: src/clojure/godotclj/api/gdscript.clj
	mkdir -p $(CLASSES)
	PATH=$(JAVA_PATH) \
	$(CLJ) -J-Dtech.v3.datatype.graal-native=true \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.spec.skip-macros=true \
		-M -e "(set! *warn-on-reflection* true) (with-bindings {#'*compile-path* (System/getenv \"CLASSES\")} (compile 'godotclj.jna-model) (compile 'godotclj.api))"

target/godotclj.jar: $(PROJECT_DIR)/deps.edn $(UBERJAR_DEPS) $(CLASSES)/godotclj/core/Bindings.class $(CLASSES)/godotclj/context.class $(GEN)/wrapper.json $(GEN)/wrapper.txt $(LIB)/natives/linux_64/libgodotclj_gdnative.so $(LIB)/natives/linux_64/libwrapper.so $(LIB)/godot-headers/api.json $(CLASSES)/godotclj/api/gdscript.class $(CLASSES)/godotclj/loader.class $(GEN)/godot_bindings.txt $(GEN)/godot_bindings.json
	mkdir -p $(shell dirname $@)
	mkdir -p $(CLASSES)
	mkdir -p $(BUILD)
	PATH=$(JAVA_PATH) \
	$(CLJ) -Sdeps '{:deps {uberdeps/uberdeps {:mvn/version "1.1.1"}}}' -M -m uberdeps.uberjar --deps-file $(PROJECT_DIR)/deps.edn --target $@ --main-class godotclj.main_jvm
