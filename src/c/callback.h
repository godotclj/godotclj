#ifndef _callback_h_
#define _callback_h_

#include <gdnative_api_struct.gen.h>

#define CALLBACK_PREFIX(name) GODOTCLJ_##name

typedef struct instance_method_callback_args {
  godot_object * ob;
  void * method_data;
  void * user_data;
  int nargs;
  godot_variant **args;
  godot_variant result;
} instance_method_callback_args;

typedef struct property_setter_func_args {
  godot_object * ob;
  void * method_data;
  void * data;
  godot_variant * variant;
} property_setter_func_args;

typedef struct property_getter_func_args {
  godot_object * ob;
  void * method_data;
  void * data;
  godot_variant result;
} property_getter_func_args;

typedef struct create_func_args {
  godot_object *ob;
  void* data;
  void* result;
} create_func_args;

typedef struct destroy_func_args {
  godot_object *ob;
  void* data;
  void* user_data;
} destroy_func_args;

typedef struct free_func_args {
  void* data;
} free_func_args;

void get_godot_instance_create_func(godot_instance_create_func* data);
void get_godot_instance_destroy_func(godot_instance_destroy_func* data);
void get_godot_property_set_func(godot_property_set_func* data);
void get_godot_property_get_func(godot_property_get_func* data);
void get_godot_instance_method(godot_instance_method* data);

#endif
