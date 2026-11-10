#include "compose_webview_internal.h"

static JavaVM *g_jvm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_on_navigate = NULL;
static jmethodID g_on_ipc = NULL;
static jmethodID g_on_channel = NULL;
static jmethodID g_on_js_result = NULL;
static jmethodID g_on_cookies = NULL;
static jmethodID g_on_cookies_removed = NULL;
static jmethodID g_on_screenshot = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEnv *compose_webview_get_env(void) {
    JNIEnv *env = NULL;
    if (g_jvm == NULL) return NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **) &env, NULL) != 0) {
            return NULL;
        }
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

void compose_webview_ensure_bridge_methods(JNIEnv *env) {
    if (g_bridge_class != NULL) return;
    jclass local = (*env)->FindClass(
        env,
        "dev/nucleusframework/webview/web/linux/WebKitLinuxBridge");
    if (local == NULL) return;
    g_bridge_class = (jclass) (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    g_on_navigate = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnNavigate", "(JLjava/lang/String;)Z");
    g_on_ipc = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnIpcMessage", "(JLjava/lang/String;)V");
    g_on_channel = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnChannelMessage", "(JJLjava/lang/String;)V");
    g_on_js_result = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnJsResult", "(JLjava/lang/String;)V");
    g_on_cookies = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnCookiesResult", "(JLjava/lang/String;)V");
    g_on_cookies_removed = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnCookiesRemoved", "(J)V");
    g_on_screenshot = (*env)->GetStaticMethodID(
        env, g_bridge_class, "nativeOnScreenshotResult", "(J[B)V");
}

jclass compose_webview_bridge_class(void) { return g_bridge_class; }
jmethodID compose_webview_on_navigate(void) { return g_on_navigate; }
jmethodID compose_webview_on_ipc(void) { return g_on_ipc; }
jmethodID compose_webview_on_channel_message(void) { return g_on_channel; }
jmethodID compose_webview_on_js_result(void) { return g_on_js_result; }
jmethodID compose_webview_on_cookies(void) { return g_on_cookies; }

/* Tells the Kotlin side that the cookie removal started with request_id has finished. */
void compose_webview_deliver_cookies_removed(jlong request_id) {
    JNIEnv *env = compose_webview_get_env();
    if (env == NULL) return;
    compose_webview_ensure_bridge_methods(env);
    if (g_bridge_class == NULL || g_on_cookies_removed == NULL) return;
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_cookies_removed, request_id);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}
jmethodID compose_webview_on_screenshot(void) { return g_on_screenshot; }

/*
 * UTF-16 conversions keep text outside the Basic Multilingual Plane intact; NewStringUTF and
 * GetStringUTFChars use JNI's modified UTF-8, which encodes it differently from the UTF-8 that
 * GLib and JavaScriptCore exchange. Both return NULL for text that is not valid in its encoding.
 */
jstring compose_webview_utf8_to_jstring(JNIEnv *env, const gchar *utf8) {
    if (utf8 == NULL) return NULL;
    glong length = 0;
    gunichar2 *utf16 = g_utf8_to_utf16(utf8, -1, NULL, &length, NULL);
    if (utf16 == NULL) return NULL;
    jstring out = (*env)->NewString(env, (const jchar *) utf16, (jsize) length);
    g_free(utf16);
    return out;
}

gchar *compose_webview_jstring_to_utf8(JNIEnv *env, jstring js) {
    if (js == NULL) return NULL;
    const jchar *chars = (*env)->GetStringChars(env, js, NULL);
    if (chars == NULL) return NULL;
    gchar *out = g_utf16_to_utf8(
        (const gunichar2 *) chars, (glong) (*env)->GetStringLength(env, js), NULL, NULL, NULL);
    (*env)->ReleaseStringChars(env, js, chars);
    return out;
}
