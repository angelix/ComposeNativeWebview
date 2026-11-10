#include "compose_webview_internal.h"

ComposeWebViewState *compose_webview_state_from_handle(jlong handle) {
    if (handle == 0) return NULL;
    return (ComposeWebViewState *) (uintptr_t) handle;
}

jlong compose_webview_handle_from_view(WebKitWebView *view) {
    gpointer p = g_object_get_data(G_OBJECT(view), "compose-webview-state");
    return (jlong) (uintptr_t) p;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_webview_web_linux_WebKitLinuxBridge_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jstring user_agent,
    jstring data_directory,
    jstring init_script,
    jstring js_bridge_script,
    jboolean incognito,
    jboolean enable_devtools,
    jboolean javascript_enabled,
    jdouble zoom_level,
    jboolean transparent,
    jfloat bg_r,
    jfloat bg_g,
    jfloat bg_b,
    jfloat bg_a,
    jstring channel_name,
    jstring channel_world,
    jstring channel_relay,
    jstring channel_shim)
{
    (void) clazz;
    compose_webview_ensure_bridge_methods(env);

    ComposeWebViewState *state = g_new0(ComposeWebViewState, 1);

    WebKitWebsiteDataManager *data_manager = NULL;
    if (incognito) {
        data_manager = webkit_website_data_manager_new_ephemeral();
    } else if (data_directory != NULL) {
        const char *dir = (*env)->GetStringUTFChars(env, data_directory, NULL);
        if (dir != NULL) {
            gchar *cache_dir = g_build_filename(dir, "cache", NULL);
            data_manager = webkit_website_data_manager_new(
                "base-data-directory", dir,
                "base-cache-directory", cache_dir,
                NULL);
            g_free(cache_dir);
            (*env)->ReleaseStringUTFChars(env, data_directory, dir);
        }
    }
    if (data_manager == NULL) {
        data_manager = webkit_website_data_manager_new_ephemeral();
    }

    state->context = webkit_web_context_new_with_website_data_manager(data_manager);
    g_object_unref(data_manager);

    state->ucm = webkit_user_content_manager_new();
    /* Connect BEFORE register to avoid racing the first postMessage. */
    state->ipc_handler = g_signal_connect(
        state->ucm,
        "script-message-received::ipc",
        G_CALLBACK(compose_webview_on_script_message),
        state);
    webkit_user_content_manager_register_script_message_handler(state->ucm, "ipc");

    /* Always inject a small window.ipc shim so the Kotlin JS bridge can attach. */
    const char *ipc_shim =
        "if (typeof window.ipc === 'undefined') {"
        "  window.ipc = {"
        "    postMessage: function(message) {"
        "      if (window.webkit && window.webkit.messageHandlers &&"
        "          window.webkit.messageHandlers.ipc) {"
        "        window.webkit.messageHandlers.ipc.postMessage("
        "          (typeof message === 'string') ? message : JSON.stringify(message)"
        "        );"
        "      }"
        "    }"
        "  };"
        "}";
    WebKitUserScript *shim = webkit_user_script_new(
        ipc_shim,
        WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
        NULL,
        NULL);
    webkit_user_content_manager_add_script(state->ucm, shim);
    webkit_user_script_unref(shim);

    /*
     * JS bridge object, built once in Kotlin. Injected at document start so
     * page scripts can call it without waiting for a post-load injection.
     */
    if (js_bridge_script != NULL) {
        const char *bridge_src = (*env)->GetStringUTFChars(env, js_bridge_script, NULL);
        if (bridge_src != NULL && bridge_src[0] != '\0') {
            WebKitUserScript *bridge = webkit_user_script_new(
                bridge_src,
                WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                NULL,
                NULL);
            webkit_user_content_manager_add_script(state->ucm, bridge);
            webkit_user_script_unref(bridge);
        }
        if (bridge_src != NULL) {
            (*env)->ReleaseStringUTFChars(env, js_bridge_script, bridge_src);
        }
    }

    /*
     * Attested message channel. WebKitGTK reports nothing about the sender of a script message,
     * so the handler is registered in a script world of its own, where only the relay script can
     * call it; the relay runs in the top frame and reports the document it runs in. The page's
     * side of the channel is installed before any init script, so an init script can use it.
     */
    if (channel_name != NULL && channel_world != NULL && channel_relay != NULL && channel_shim != NULL) {
        const char *name = (*env)->GetStringUTFChars(env, channel_name, NULL);
        const char *world = (*env)->GetStringUTFChars(env, channel_world, NULL);
        const char *relay_src = (*env)->GetStringUTFChars(env, channel_relay, NULL);
        const char *shim_src = (*env)->GetStringUTFChars(env, channel_shim, NULL);
        if (name != NULL && world != NULL && relay_src != NULL && shim_src != NULL) {
            state->pending_replies = g_hash_table_new_full(
                g_int64_hash, g_int64_equal, g_free,
                (GDestroyNotify) webkit_script_message_reply_unref);
            state->reply_context = jsc_context_new();

            /* Connect BEFORE register, as for "ipc" above. */
            gchar *signal = g_strdup_printf("script-message-with-reply-received::%s", name);
            state->channel_handler = g_signal_connect(
                state->ucm, signal, G_CALLBACK(compose_webview_on_channel_script_message), state);
            g_free(signal);
            webkit_user_content_manager_register_script_message_handler_with_reply(
                state->ucm, name, world);

            WebKitUserScript *relay = webkit_user_script_new_for_world(
                relay_src,
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                world,
                NULL,
                NULL);
            webkit_user_content_manager_add_script(state->ucm, relay);
            webkit_user_script_unref(relay);

            WebKitUserScript *channel_page_shim = webkit_user_script_new(
                shim_src,
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                NULL,
                NULL);
            webkit_user_content_manager_add_script(state->ucm, channel_page_shim);
            webkit_user_script_unref(channel_page_shim);
        }
        if (name != NULL) (*env)->ReleaseStringUTFChars(env, channel_name, name);
        if (world != NULL) (*env)->ReleaseStringUTFChars(env, channel_world, world);
        if (relay_src != NULL) (*env)->ReleaseStringUTFChars(env, channel_relay, relay_src);
        if (shim_src != NULL) (*env)->ReleaseStringUTFChars(env, channel_shim, shim_src);
    }

    /*
     * Opaque mode: force a solid page background. Many pages (and about:blank)
     * leave html/body transparent; without this the Compose clear-through
     * NativeView hole shows UI underneath the WebView.
     */
    if (!transparent) {
        WebKitUserStyleSheet *sheet = webkit_user_style_sheet_new(
            "html, body { background-color: #ffffff !important; }",
            WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
            WEBKIT_USER_STYLE_LEVEL_USER,
            NULL,
            NULL);
        webkit_user_content_manager_add_style_sheet(state->ucm, sheet);
        webkit_user_style_sheet_unref(sheet);
    }

    if (init_script != NULL) {
        const char *src = (*env)->GetStringUTFChars(env, init_script, NULL);
        if (src != NULL && src[0] != '\0') {
            WebKitUserScript *user_script = webkit_user_script_new(
                src,
                WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                NULL,
                NULL);
            webkit_user_content_manager_add_script(state->ucm, user_script);
            webkit_user_script_unref(user_script);
        }
        if (src != NULL) {
            (*env)->ReleaseStringUTFChars(env, init_script, src);
        }
    }

    WebKitSettings *settings = webkit_settings_new();
    webkit_settings_set_enable_javascript(settings, javascript_enabled ? TRUE : FALSE);
    webkit_settings_set_enable_developer_extras(settings, enable_devtools ? TRUE : FALSE);
    webkit_settings_set_javascript_can_access_clipboard(settings, TRUE);
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, FALSE);
    if (user_agent != NULL) {
        const char *ua = (*env)->GetStringUTFChars(env, user_agent, NULL);
        if (ua != NULL && ua[0] != '\0') {
            webkit_settings_set_user_agent(settings, ua);
        }
        if (ua != NULL) {
            (*env)->ReleaseStringUTFChars(env, user_agent, ua);
        }
    }

    state->web_view = WEBKIT_WEB_VIEW(g_object_new(
        WEBKIT_TYPE_WEB_VIEW,
        "web-context", state->context,
        "user-content-manager", state->ucm,
        "settings", settings,
        NULL));
    g_object_unref(settings);

    /* Strong floating-ref so the widget survives until nativeRelease. */
    g_object_ref_sink(G_OBJECT(state->web_view));

    if (zoom_level > 0.0) {
        webkit_web_view_set_zoom_level(state->web_view, zoom_level);
    }

    /* Opaque browser default = solid white when transparent is off. */
    GdkRGBA bg;
    if (transparent) {
        bg.red = bg_r;
        bg.green = bg_g;
        bg.blue = bg_b;
        bg.alpha = bg_a;
    } else {
        bg.red = (bg_a >= 1.0f) ? bg_r : 1.0;
        bg.green = (bg_a >= 1.0f) ? bg_g : 1.0;
        bg.blue = (bg_a >= 1.0f) ? bg_b : 1.0;
        bg.alpha = 1.0;
    }
    webkit_web_view_set_background_color(state->web_view, &bg);
    gtk_widget_set_opacity(GTK_WIDGET(state->web_view), 1.0);

    g_object_set_data(G_OBJECT(state->web_view), "compose-webview-state", state);

    state->decide_policy_handler = g_signal_connect(
        state->web_view,
        "decide-policy",
        G_CALLBACK(compose_webview_on_decide_policy),
        state);

    return (jlong) (uintptr_t) state;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_webview_web_linux_WebKitLinuxBridge_nativeGetGtkWidget(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    ComposeWebViewState *state = compose_webview_state_from_handle(handle);
    if (state == NULL || state->web_view == NULL) return 0;
    return (jlong) (uintptr_t) GTK_WIDGET(state->web_view);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_linux_WebKitLinuxBridge_nativeRelease(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    ComposeWebViewState *state = compose_webview_state_from_handle(handle);
    if (state == NULL) return;

    if (state->web_view != NULL) {
        if (state->decide_policy_handler != 0) {
            g_signal_handler_disconnect(state->web_view, state->decide_policy_handler);
            state->decide_policy_handler = 0;
        }
        g_object_set_data(G_OBJECT(state->web_view), "compose-webview-state", NULL);
        g_object_unref(state->web_view);
        state->web_view = NULL;
    }
    if (state->pending_replies != NULL) {
        /* Reject what the host never answered; destroying the table drops the reply objects. */
        GHashTableIter iter;
        gpointer reply = NULL;
        g_hash_table_iter_init(&iter, state->pending_replies);
        while (g_hash_table_iter_next(&iter, NULL, &reply)) {
            webkit_script_message_reply_return_error_message(
                (WebKitScriptMessageReply *) reply, "WebView released");
        }
        g_hash_table_destroy(state->pending_replies);
        state->pending_replies = NULL;
    }
    if (state->reply_context != NULL) {
        g_object_unref(state->reply_context);
        state->reply_context = NULL;
    }
    if (state->ucm != NULL) {
        if (state->ipc_handler != 0) {
            g_signal_handler_disconnect(state->ucm, state->ipc_handler);
            state->ipc_handler = 0;
        }
        if (state->channel_handler != 0) {
            g_signal_handler_disconnect(state->ucm, state->channel_handler);
            state->channel_handler = 0;
        }
        g_object_unref(state->ucm);
        state->ucm = NULL;
    }
    if (state->context != NULL) {
        g_object_unref(state->context);
        state->context = NULL;
    }
    g_free(state);
}


/*
 * Answers one message on the attested channel. A NULL payload, or one that is not valid UTF-16,
 * rejects the page's promise. Unknown ids (already answered, or belonging to a released WebView)
 * are ignored.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_linux_WebKitLinuxBridge_nativeChannelReply(
    JNIEnv *env, jclass clazz, jlong handle, jlong reply_id, jstring payload)
{
    (void) clazz;
    ComposeWebViewState *state = compose_webview_state_from_handle(handle);
    if (state == NULL || state->pending_replies == NULL) return;

    gint64 key = (gint64) reply_id;
    gpointer stored_key = NULL;
    gpointer stored_reply = NULL;
    if (!g_hash_table_steal_extended(state->pending_replies, &key, &stored_key, &stored_reply)) return;
    g_free(stored_key);
    WebKitScriptMessageReply *reply = (WebKitScriptMessageReply *) stored_reply;

    gchar *text = compose_webview_jstring_to_utf8(env, payload);
    if (text == NULL) {
        webkit_script_message_reply_return_error_message(
            reply, payload == NULL ? "Message could not be delivered" : "Reply could not be delivered");
    } else {
        JSCValue *value = jsc_value_new_string(state->reply_context, text);
        webkit_script_message_reply_return_value(reply, value);
        g_object_unref(value);
        g_free(text);
    }
    webkit_script_message_reply_unref(reply);
}
