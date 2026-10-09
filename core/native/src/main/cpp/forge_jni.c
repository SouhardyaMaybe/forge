/*
 * Forge native PTY layer.
 *
 * Provides the pseudoterminal primitives the terminal and agent hosts need.
 * The PTY is assembled from POSIX calls (posix_openpt / grantpt / unlockpt /
 * ptsname) instead of libutil's forkpty so the library links cleanly against
 * Android's bionic libc with no extra system libraries.
 *
 * Handles are packed into a single jlong: (masterFd << 32) | childPid.
 * Everything else (session durability, tmux attach, journaling) lives in
 * Kotlin; this file deliberately stays small and boring.
 */

#include <jni.h>
#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/waitpid.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "forge-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int32_t handle_master_fd(jlong handle) {
    return (int32_t)(handle >> 32);
}

static int32_t handle_child_pid(jlong handle) {
    return (int32_t)(handle & 0xFFFFFFFFL);
}

static jlong pack_handle(int32_t fd, int32_t pid) {
    return ((jlong)fd << 32) | ((jlong)(uint32_t)pid);
}

JNIEXPORT jlong JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeOpen(
        JNIEnv *env,
        jclass clazz,
        jobjectArray argv,
        jstring cwd,
        jobjectArray envp,
        jint rows,
        jint cols) {
    if (argv == NULL) {
        return -1;
    }

    jsize argc = (*env)->GetArrayLength(env, argv);
    if (argc <= 0) {
        return -1;
    }

    // Convert the argv array into a NULL-terminated char** for execvp.
    char **c_argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    char **c_envp = NULL;
    if (c_argv == NULL) {
        return -1;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
        if (item == NULL) {
            goto fail_free_argv;
        }
        const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (utf == NULL) {
            (*env)->DeleteLocalRef(env, item);
            goto fail_free_argv;
        }
        c_argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, item, utf);
        (*env)->DeleteLocalRef(env, item);
        if (c_argv[i] == NULL) {
            goto fail_free_argv;
        }
    }
    c_argv[argc] = NULL;

    jsize envc = 0;
    if (envp != NULL) {
        envc = (*env)->GetArrayLength(env, envp);
        c_envp = (char **)calloc((size_t)envc + 1, sizeof(char *));
        if (c_envp == NULL) {
            goto fail_free_argv;
        }
        for (jsize i = 0; i < envc; i++) {
            jstring item = (jstring)(*env)->GetObjectArrayElement(env, envp, i);
            const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
            c_envp[i] = strdup(utf != NULL ? utf : "");
            (*env)->ReleaseStringUTFChars(env, item, utf);
            (*env)->DeleteLocalRef(env, item);
        }
        c_envp[envc] = NULL;
    }

    const char *c_cwd = NULL;
    if (cwd != NULL) {
        c_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        goto fail_free_all;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        goto fail_free_all;
    }

    const char *slave_name = ptsname(master);
    if (slave_name == NULL) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(master);
        goto fail_free_all;
    }

    int slave = open(slave_name, O_RDWR);
    if (slave < 0) {
        LOGE("open slave failed: %s", strerror(errno));
        close(master);
        goto fail_free_all;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(slave, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(slave);
        close(master);
        goto fail_free_all;
    }

    if (pid == 0) {
        // Child: new session, controlling terminal, stdio wired to the slave.
        setsid();
#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) {
            close(slave);
        }
        close(master);

        if (c_cwd != NULL) {
            if (chdir(c_cwd) != 0) {
                LOGE("chdir(%s) failed: %s", c_cwd, strerror(errno));
            }
        }

        if (c_envp != NULL) {
            extern char **environ;
            environ = c_envp;
        }

        execvp(c_argv[0], c_argv);
        LOGE("execvp(%s) failed: %s", c_argv[0], strerror(errno));
        _exit(127);
    }

    // Parent: the slave side is not needed any more.
    close(slave);

    for (jsize i = 0; i < argc; i++) {
        free(c_argv[i]);
    }
    free(c_argv);
    if (c_envp != NULL) {
        for (jsize i = 0; i < envc; i++) {
            free(c_envp[i]);
        }
        free(c_envp);
    }
    if (c_cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    }

    LOGI("pty open: fd=%d pid=%d cmd=%s", master, pid, c_argv[0] != NULL ? c_argv[0] : "?");
    return pack_handle(master, (int32_t)pid);

fail_free_all:
    if (c_cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    }
    if (c_envp != NULL) {
        for (jsize i = 0; i < envc; i++) {
            free(c_envp[i]);
        }
        free(c_envp);
    }
fail_free_argv:
    for (jsize i = 0; i < argc; i++) {
        free(c_argv[i]);
    }
    free(c_argv);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeRead(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray buffer,
        jint offset,
        jint length) {
    if (buffer == NULL || length <= 0) {
        return 0;
    }
    int fd = handle_master_fd(handle);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -1;
    }
    ssize_t n = read(fd, bytes + offset, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    if (n < 0) {
        return -1;
    }
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeWrite(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray buffer,
        jint offset,
        jint length) {
    if (buffer == NULL || length <= 0) {
        return 0;
    }
    int fd = handle_master_fd(handle);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -1;
    }

    ssize_t total = 0;
    while (total < length) {
        ssize_t n = write(fd, bytes + offset + total, (size_t)(length - total));
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
            return -1;
        }
        total += n;
    }

    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return (jint)total;
}

JNIEXPORT void JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeResize(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jint rows,
        jint cols) {
    int fd = handle_master_fd(handle);
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeSignal(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jint signal) {
    kill(handle_child_pid(handle), signal);
}

JNIEXPORT void JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeClose(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    int fd = handle_master_fd(handle);
    if (fd >= 0) {
        close(fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeWait(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    int pid = handle_child_pid(handle);
    if (pid <= 0) {
        return -1;
    }
    int status = 0;
    pid_t result = waitpid(pid, &status, WNOHANG);
    if (result == 0) {
        // Still running.
        return -1;
    }
    if (result < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT jintArray JNICALL
Java_com_forge_ide_core_native_PtyNativeKt_nativeMemInfo(
        JNIEnv *env,
        jclass clazz) {
    // Returns [totalKb, availableKb] parsed from /proc/meminfo.
    long total_kb = -1;
    long available_kb = -1;

    FILE *f = fopen("/proc/meminfo", "r");
    if (f != NULL) {
        char line[256];
        while (fgets(line, sizeof(line), f) != NULL) {
            if (strncmp(line, "MemTotal:", 9) == 0) {
                total_kb = strtol(line + 9, NULL, 10);
            } else if (strncmp(line, "MemAvailable:", 13) == 0) {
                available_kb = strtol(line + 13, NULL, 10);
            }
        }
        fclose(f);
    }

    jint values[2];
    values[0] = (jint)total_kb;
    values[1] = (jint)available_kb;

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    }
    return result;
}
