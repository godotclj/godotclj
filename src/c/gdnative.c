#include <stdio.h>
#include <stdlib.h>
#include <gdnative_api_struct.gen.h>

extern const godot_gdnative_core_api_struct *api;
extern const godot_gdnative_ext_nativescript_api_struct *nativescript_api;

#ifdef RUNTIME_GRAALVM
#include <libgodotclj.h>

graal_isolatethread_t* thread = NULL;
#else
#include <jvm.h>
#endif

void GDN_EXPORT godot_gdnative_init(godot_gdnative_init_options *p_options) {
  api = p_options->api_struct;

  for (int i = 0; i < api->num_extensions; i++) {
    if (GDNATIVE_EXT_NATIVESCRIPT == api->extensions[i]->type) {
      nativescript_api = (godot_gdnative_ext_nativescript_api_struct *)api->extensions[i];
      break;
    }
  }
}

void GDN_EXPORT godot_gdnative_terminate(godot_gdnative_terminate_options *p_options) {
  api = NULL;
  nativescript_api = NULL;
}

void GDN_EXPORT godot_nativescript_terminate(void* lib_path) {
}

void GDN_EXPORT godot_nativescript_init(void *p_h) {
  int result;

#ifdef RUNTIME_GRAALVM
  if (graal_create_isolate(NULL, NULL, &thread) != 0) {
    fprintf(stderr, "graal_create_isolate error\n");
    return;
  }

  godot_nativescript_init_clojure(thread, p_h);
#else
  result = clojure_call("godotclj.main-jvm", "godot_nativescript_init_clojure", p_h);
  if (result < 0) {
    fprintf(stderr, "godot_nativescript_init: could not init JVM\n");
    return;
  };
#endif
}
