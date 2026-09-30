// JNI-мост: lwip_bridge.c (чистый C, без изменений из iOS) ↔ Kotlin.
// Все колбэки вызывают статические методы LwipNative; они приходят только
// с потока, на котором вызваны input/poll/write (single-thread executor
// в Kotlin), поэтому AttachCurrentThread — страховка, а не рабочий путь.
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include "lwip_bridge.h"

#define TAG "WSBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;
static jclass g_cls = NULL; // LwipNative (global ref)
static jmethodID m_output, m_accept, m_recv, m_close, m_sent, m_log;

static JNIEnv *get_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return NULL;
    *attached = 1;
    return env;
}

static void detach_if(int attached) {
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void cb_output(const uint8_t *data, uint16_t len, void *ctx) {
    (void)ctx;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
        (*env)->CallStaticVoidMethod(env, g_cls, m_output, arr);
        (*env)->DeleteLocalRef(env, arr);
    }
    detach_if(attached);
}

static void cb_accept(uint32_t conn_id, void *ctx) {
    (void)ctx;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    uint32_t dc_ip = lwip_bridge_get_dst_ip(conn_id);
    // Порт iOS-диагностики (LWIPBridge.swift): dc_ip == 0 означает промах NAT-lookup,
    // и без этой строки в журнале остаётся голое accept:c без причины.
    if (dc_ip == 0) {
        uint32_t ki = 0, dc = 0, ni = 0, nd = 0; uint16_t kp = 0, np = 0;
        lwip_bridge_dbg_nat(&ki, &kp, &dc, &ni, &np, &nd);
        char buf[160];
        snprintf(buf, sizeof(buf), "natmiss:key=%08x:%u dc=%08x nat0=%08x:%u->%08x",
                 ki, kp, dc, ni, np, nd);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", buf);
        jstring msg = (*env)->NewStringUTF(env, buf);
        if (msg) {
            (*env)->CallStaticVoidMethod(env, g_cls, m_log, msg);
            (*env)->DeleteLocalRef(env, msg);
        }
    }
    (*env)->CallStaticVoidMethod(env, g_cls, m_accept, (jlong)conn_id, (jlong)dc_ip);
    detach_if(attached);
}

static void cb_recv(uint32_t conn_id, const uint8_t *data, uint16_t len, void *ctx) {
    (void)ctx;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
        (*env)->CallStaticVoidMethod(env, g_cls, m_recv, (jlong)conn_id, arr);
        (*env)->DeleteLocalRef(env, arr);
    }
    detach_if(attached);
}

static void cb_close(uint32_t conn_id, int32_t reason, void *ctx) {
    (void)ctx;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_close, (jlong)conn_id, (jint)reason);
    detach_if(attached);
}

static void cb_sent(uint32_t conn_id, void *ctx) {
    (void)ctx;
    int attached; JNIEnv *env = get_env(&attached);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_sent, (jlong)conn_id);
    detach_if(attached);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/l1ratch/wsbridge/tunnel/LwipNative");
    if (!cls) { LOGE("LwipNative class not found"); return JNI_ERR; }
    g_cls = (jclass)(*env)->NewGlobalRef(env, cls);
    m_output = (*env)->GetStaticMethodID(env, g_cls, "onOutput", "([B)V");
    m_accept = (*env)->GetStaticMethodID(env, g_cls, "onAccept", "(JJ)V");
    m_recv   = (*env)->GetStaticMethodID(env, g_cls, "onRecv", "(J[B)V");
    m_close  = (*env)->GetStaticMethodID(env, g_cls, "onClose", "(JI)V");
    m_sent   = (*env)->GetStaticMethodID(env, g_cls, "onSent", "(J)V");
    m_log    = (*env)->GetStaticMethodID(env, g_cls, "log", "(Ljava/lang/String;)V");
    if (!m_output || !m_accept || !m_recv || !m_close || !m_sent || !m_log) {
        LOGE("LwipNative callback method not found");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeInit(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    lwip_bridge_init(NULL, cb_output, cb_accept, cb_recv, cb_close, cb_sent);
}

JNIEXPORT void JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeInput(JNIEnv *env, jclass cls, jbyteArray data, jint len) {
    (void)cls;
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return;
    lwip_bridge_input((const uint8_t *)buf, (uint16_t)len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativePoll(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    lwip_bridge_poll();
}

JNIEXPORT jint JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeWrite(JNIEnv *env, jclass cls, jlong connId, jbyteArray data, jint len) {
    (void)cls;
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -1;
    int r = lwip_bridge_write((uint32_t)connId, (const uint8_t *)buf, (uint16_t)len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return r;
}

JNIEXPORT void JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeClose(JNIEnv *env, jclass cls, jlong connId) {
    (void)env; (void)cls;
    lwip_bridge_close((uint32_t)connId);
}

JNIEXPORT jlong JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeGetDstIp(JNIEnv *env, jclass cls, jlong connId) {
    (void)env; (void)cls;
    return (jlong)lwip_bridge_get_dst_ip((uint32_t)connId);
}

JNIEXPORT jlong JNICALL
Java_com_l1ratch_wsbridge_tunnel_LwipNative_nativeInmemDrops(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)lwip_bridge_inmem_drops();
}
