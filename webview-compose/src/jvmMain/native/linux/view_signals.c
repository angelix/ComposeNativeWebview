#include "compose_webview_internal.h"

gboolean compose_webview_on_decide_policy(
    WebKitWebView *web_view,
    WebKitPolicyDecision *decision,
    WebKitPolicyDecisionType type,
    gpointer user_data)
{
    (void) user_data;
    if (type != WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION &&
        type != WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
        return FALSE;
    }

    WebKitNavigationPolicyDecision *nav =
        WEBKIT_NAVIGATION_POLICY_DECISION(decision);
    WebKitNavigationAction *action =
        webkit_navigation_policy_decision_get_navigation_action(nav);
    WebKitURIRequest *request = webkit_navigation_action_get_request(action);
    const gchar *uri = webkit_uri_request_get_uri(request);
    if (uri == NULL) {
        webkit_policy_decision_use(decision);
        return TRUE;
    }

    if (g_str_has_prefix(uri, "about:") ||
        g_str_has_prefix(uri, "data:") ||
        g_str_has_prefix(uri, "blob:")) {
        webkit_policy_decision_use(decision);
        return TRUE;
    }

    JNIEnv *env = compose_webview_get_env();
    if (env == NULL) {
        webkit_policy_decision_use(decision);
        return TRUE;
    }
    compose_webview_ensure_bridge_methods(env);
    if (compose_webview_bridge_class() == NULL || compose_webview_on_navigate() == NULL) {
        webkit_policy_decision_use(decision);
        return TRUE;
    }

    jlong handle = compose_webview_handle_from_view(web_view);
    jstring juri = (*env)->NewStringUTF(env, uri);
    jboolean allow = (*env)->CallStaticBooleanMethod(
        env, compose_webview_bridge_class(), compose_webview_on_navigate(), handle, juri);
    (*env)->DeleteLocalRef(env, juri);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        webkit_policy_decision_use(decision);
        return TRUE;
    }

    if (allow) {
        webkit_policy_decision_use(decision);
    } else {
        webkit_policy_decision_ignore(decision);
    }
    return TRUE;
}

void compose_webview_on_script_message(
    WebKitUserContentManager *manager,
    WebKitJavascriptResult *js_result,
    gpointer user_data)
{
    (void) manager;
    ComposeWebViewState *state = (ComposeWebViewState *) user_data;
    if (state == NULL || state->web_view == NULL || js_result == NULL) return;

    /* WebKitGTK 4.1 still delivers WebKitJavascriptResult on this signal
     * (not a bare JSCValue*). Extract the JSCValue first. */
    JSCValue *value = webkit_javascript_result_get_js_value(js_result);
    if (value == NULL || !JSC_IS_VALUE(value)) return;

    gchar *message = NULL;
    if (jsc_value_is_string(value)) {
        message = jsc_value_to_string(value);
    } else {
        message = jsc_value_to_json(value, 0);
    }
    if (message == NULL) return;

    JNIEnv *env = compose_webview_get_env();
    if (env != NULL) {
        compose_webview_ensure_bridge_methods(env);
        if (compose_webview_bridge_class() != NULL && compose_webview_on_ipc() != NULL) {
            jlong handle = (jlong) (uintptr_t) state;
            jstring jmsg = (*env)->NewStringUTF(env, message);
            (*env)->CallStaticVoidMethod(
                env, compose_webview_bridge_class(), compose_webview_on_ipc(), handle, jmsg);
            (*env)->DeleteLocalRef(env, jmsg);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        }
    }
    g_free(message);
}

/* Reply ids are unique across every WebView, so a reply held for one WebView can never match a
 * request of another that later reuses its state's memory address. GTK main thread only. */
static gint64 g_next_reply_id = 0;

/*
 * A message on the attested channel. Only the relay script in the channel's script world can reach
 * this handler; `value` is its envelope (origin, main frame or not, and the page's body), which the
 * Kotlin side decodes. The reply object is kept until the host answers: WebKit resolves the relay's
 * promise with it, in the document that sent the message and no other.
 */
gboolean compose_webview_on_channel_script_message(
    WebKitUserContentManager *manager,
    JSCValue *value,
    WebKitScriptMessageReply *reply,
    gpointer user_data)
{
    (void) manager;
    ComposeWebViewState *state = (ComposeWebViewState *) user_data;
    if (state == NULL || state->pending_replies == NULL ||
        value == NULL || !JSC_IS_VALUE(value) || !jsc_value_is_string(value)) {
        webkit_script_message_reply_return_error_message(reply, "Unsupported message");
        return TRUE;
    }

    JNIEnv *env = compose_webview_get_env();
    if (env != NULL) compose_webview_ensure_bridge_methods(env);
    if (env == NULL || compose_webview_bridge_class() == NULL ||
        compose_webview_on_channel_message() == NULL) {
        webkit_script_message_reply_return_error_message(reply, "Host unavailable");
        return TRUE;
    }

    gchar *envelope = jsc_value_to_string(value);
    jstring jenvelope = compose_webview_utf8_to_jstring(env, envelope);
    g_free(envelope);
    if (jenvelope == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        webkit_script_message_reply_return_error_message(reply, "Message could not be delivered");
        return TRUE;
    }

    /* The host may answer before the call below returns, which removes the entry and frees its key. */
    gint64 reply_id = ++g_next_reply_id;
    gint64 *key = g_new(gint64, 1);
    *key = reply_id;
    g_hash_table_insert(state->pending_replies, key, webkit_script_message_reply_ref(reply));

    (*env)->CallStaticVoidMethod(
        env, compose_webview_bridge_class(), compose_webview_on_channel_message(),
        (jlong) (uintptr_t) state, (jlong) reply_id, jenvelope);
    (*env)->DeleteLocalRef(env, jenvelope);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return TRUE;
}
