#include <jni.h>
#include <string>
#include <stdlib.h>
#include <cstring>
#include <stdarg.h>
#include <iostream>
#include<pthread.h>

extern "C" {
  JavaVM *vm;
  JNIEnv *env = NULL;

  int createVM(void) {
    JavaVMInitArgs vm_args;
    JavaVMOption* options = new JavaVMOption[1];

    char* classpath_env = getenv("CLASSPATH");

    if (classpath_env == NULL) {
      printf("CLASSPATH is missing\n");
      return -1;
    }

    char* classpath = (char*)malloc(sizeof(char)*strlen(classpath_env)+1);

    strcpy(classpath, classpath_env);

    std::string classPathPrefix = "-Djava.class.path=";
    std::string optionString = classPathPrefix + std::string(classpath);

    options[0].optionString = (char*)optionString.c_str();

    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = 1;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = false;

    int result = JNI_CreateJavaVM(&vm, (void**)&env, &vm_args);
    delete options;
    free(classpath);

    return result;
  }

  int attach() {
    if (!vm) {
      fprintf(stderr, "vm is null: %p %p\n", vm);
      return -1;
    }

    return vm->AttachCurrentThread((void**)&env, NULL);
  }

  int detach() {
    if (!vm) {
      fprintf(stderr, "detach: no vm\n");
      return -1;
    }

    return vm->DetachCurrentThread();
  }

  int ensureJvm() {
    if (env) {
      return 0;
    }

    JavaVM *vms[1];
    jsize nvms;

    if (JNI_GetCreatedJavaVMs(vms, 1, &nvms) < 0) {
      return -1;
    }

    if (nvms > 0) {
      vm = vms[0];
      return 0;
    }

    return createVM();
  }

  int java_call(const char* class_name, const char* function_name, const char* clojure_namespace_name, const char* clojure_function_name, void* arg) {
    jclass cls = env->FindClass(class_name);

    if (!cls) {
      fprintf(stderr, "Class '%s' not found\n", class_name);
      return -1;
    }

    jmethodID mid = env->GetStaticMethodID(cls, function_name, "(Ljava/lang/String;Ljava/lang/String;J)V");
    if (!mid) {
      fprintf(stderr, "function '%s' not found\n", function_name);
      return -1;
    }

    env->CallStaticVoidMethod(cls, mid, env->NewStringUTF(clojure_namespace_name), env->NewStringUTF(clojure_function_name), (long)arg);

    return 0;
  }

  pthread_mutex_t thread_lock;
  pthread_mutex_t info_lock;
  pthread_t thread_id;
  int thread_lock_count = 0;

  int clojure_call(const char* namespace_name, const char* function_name, void* arg) {
    int result = 0;

    pthread_mutex_lock(&info_lock);

    pthread_t entering = pthread_self();

    if (thread_lock_count == 0) {
      pthread_mutex_lock(&thread_lock);
      if (ensureJvm() < 0) {
        fprintf(stderr, "VM not available\n");
        goto CLOJURE_CALL_JVM_ERROR;
      }

      if (attach() < 0) {
        fprintf(stderr, "Could not attach thread\n");
        goto CLOJURE_CALL_JVM_ERROR;
      }
    } else if (!pthread_equal(entering, thread_id)) {
      pthread_mutex_unlock(&info_lock);
      pthread_mutex_lock(&thread_lock);
      pthread_mutex_lock(&info_lock);
    }

    thread_id = entering;
    thread_lock_count++;
    pthread_mutex_unlock(&info_lock);

    // TOOD see if stringUTF must be freed
    result = java_call("godotclj/loader", "clojure_call", namespace_name, function_name, arg);

    pthread_mutex_lock(&info_lock);
    if (--thread_lock_count == 0) {
      int error;
      if ((error = detach()) < 0) {
        fprintf(stderr, "Could not detach thread: %i %s %s\n", error, namespace_name, function_name);
        goto CLOJURE_CALL_JVM_ERROR;
      }

      pthread_mutex_unlock(&thread_lock);
    }
    pthread_mutex_unlock(&info_lock);

    return result;
  CLOJURE_CALL_JVM_ERROR:
    pthread_mutex_unlock(&thread_lock);
    pthread_mutex_unlock(&info_lock);
    return -1;
  }

  void jvmstop(void) {
      printf("destroy vm\n");
      vm->DestroyJavaVM();
      printf("destroy(d) vm\n");
  }
}
