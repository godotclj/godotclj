#include <stdio.h>
#include <callback.h>

#ifdef RUNTIME_GENERATION
  #define CALLBACK(name, args)
#elif RUNTIME_GRAALVM
  #include <graal_isolate.h>
  #include <libgodotclj.h>

  extern graal_isolatethread_t* thread;

  #define CALLBACK(name, args) name(thread, args)
#else
  #include <jvm.h>

  #define CALLBACK(name, args) clojure_call("godotclj.bindings.godot", #name, args);
#endif

void* CALLBACK_PREFIX(godot_create_func)(godot_object *ob, void* data) {
  create_func_args args = { ob, data };

  //  CALLBACK(create_func_callback, (void*)&args);

  return args.result;
}

void CALLBACK_PREFIX(godot_destroy_func)(godot_object *ob, void* data, void* user_data) {
  destroy_func_args args = { ob, data, user_data };

  // CALLBACK(destroy_func_callback, (void*)&args);
}

void CALLBACK_PREFIX(godot_free_func)(void* data) {
  free_func_args args = { data };

  // CALLBACK(free_func_callback, (void*)&args);
}

void CALLBACK_PREFIX(godot_property_setter_func)(godot_object * ob, void * method_data, void * data, godot_variant * variant) {
  property_setter_func_args args = { ob, method_data, data, variant };

  CALLBACK(property_set_func_callback, (void*)&args);
}

godot_variant CALLBACK_PREFIX(godot_property_getter_func)(godot_object * ob, void * method_data, void * data) {
  property_getter_func_args args = { ob, method_data, data };

  CALLBACK(property_get_func_callback, (void*)&args);

  return args.result;
}

godot_variant CALLBACK_PREFIX(godot_instance_method_callback)(godot_object * ob, void * method_data, void * user_data, int nargs, godot_variant **p_args) {
  instance_method_callback_args args = { ob, method_data, user_data, nargs, p_args };

  CALLBACK(instance_method_callback, (void*)&args);

  return args.result;
}

void get_godot_instance_create_func(godot_instance_create_func* data) {
  data->create_func = &CALLBACK_PREFIX(godot_create_func);
  data->free_func = &CALLBACK_PREFIX(godot_free_func);
}

void get_godot_instance_destroy_func(godot_instance_destroy_func* data) {
  data->destroy_func = &CALLBACK_PREFIX(godot_destroy_func);
  data->free_func = &CALLBACK_PREFIX(godot_free_func);
}

void get_godot_property_set_func(godot_property_set_func* data) {
  data->set_func = &CALLBACK_PREFIX(godot_property_setter_func);
  data->free_func = &CALLBACK_PREFIX(godot_free_func);
}

void get_godot_property_get_func(godot_property_get_func* data) {
    data->get_func = &CALLBACK_PREFIX(godot_property_getter_func);
    data->free_func = &CALLBACK_PREFIX(godot_free_func);
}

void get_godot_instance_method(godot_instance_method* data) {
    data->method = &CALLBACK_PREFIX(godot_instance_method_callback);
    data->free_func = &CALLBACK_PREFIX(godot_free_func);
}
