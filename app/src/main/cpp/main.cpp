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

jint SafetyChecker_forkOrphan(JNIEnv* env, jclass, jstring apk) {
    // After forking we are no longer able to use JNI
    auto orig_apk_path = env->GetStringUTFChars(apk, nullptr);
    auto apk_path = strdup(orig_apk_path);
    env->ReleaseStringUTFChars(apk, orig_apk_path);

    // Create pipe to communicate with the child process
    // Do not use O_CLOEXEC as we want to write to pipe after exec
    int fd[2];
    if (pipe(fd) == -1) return -1;
    int read_fd = fd[0];
    int write_fd = fd[1];

    pid_t pid = fork();
    if (pid < 0) return pid; // fork failed
    if (pid == 0) { // child process
        close(read_fd);
        pid = fork();
        if (pid > 0) {
            exit(0);
        } else if (pid < 0) {
            // fork failed, exit to trigger EOFException when reader reads from pipe
            LOGE("fork() failed with %d: %s", errno, strerror(errno));
            close(write_fd);
            abort();
        }
        // pid == 0, ensure orphan process
        kill(getppid(), SIGKILL);

        // After fork we cannot call many functions including JNI (otherwise we may deadlock)
        // Call execl() to recreate runtime and run our checking code
        char fd_arg[32];
        snprintf(fd_arg, sizeof(fd_arg), "%d", write_fd);
        setenv("CLASSPATH", apk_path, 1);
        execl("/system/bin/app_process",
              "/system/bin/app_process",
              "/system/bin",
              "top.canyie.magiskkiller.SubprocessMain",
              "--write-fd",
              strdup(fd_arg),
              (char*) nullptr);

        // execl() only returns if failed
        LOGE("execl() failed with %d: %s", errno, strerror(errno));
        abort();
    }
    // parent process
    free(apk_path);
    close(write_fd);
    return read_fd;
}

static const JNINativeMethod JNI_METHODS[] = {
        {"forkOrphan", "(Ljava/lang/String;)I", (void*) SafetyChecker_forkOrphan}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv* env;
    jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    jclass cls = env->FindClass("top/canyie/magiskkiller/MagiskKiller");
    env->RegisterNatives(cls, JNI_METHODS, 1);
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}

