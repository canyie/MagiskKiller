//
// Created by canyie on 2021/4/24.
//

#include <string_view>
#include <cstdlib>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <fcntl.h>

#define LOG_TAG "MagiskKiller"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, __VA_ARGS__)

jint SafetyChecker_forkOrphan(JNIEnv* env, jclass, jint read_fd, jint write_fd) {
    pid_t pid = fork();
    if (pid < 0) return pid; // fork failed
    if (pid == 0) { // child process
        pid = fork();
        char tmp[16];
        if (pid == 0) {
            kill(getppid(), SIGKILL);
            snprintf(tmp, sizeof(tmp), "%d", getpid());
            write(write_fd, tmp, sizeof(tmp));
        } else {
            if (pid < 0) { // fork failed
                snprintf(tmp, sizeof(tmp), "%d", pid);
                write(write_fd, tmp, sizeof(tmp));
            }
            exit(0);
        }
    } else {
        char tmp[16];
        tmp[read(read_fd, tmp, sizeof(tmp))] = '\0';
        pid = atoi(tmp);
    }
    return pid;
}

static const JNINativeMethod JNI_METHODS[] = {
        {"forkOrphan", "(II)I", (void*) SafetyChecker_forkOrphan}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv* env;
    jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    jclass cls = env->FindClass("top/canyie/magiskkiller/MagiskKiller");
    env->RegisterNatives(cls, JNI_METHODS, 1);
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}

