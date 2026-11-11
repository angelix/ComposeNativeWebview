#include "compose_webview_internal.h"

#include <sstream>

using Microsoft::WRL::Callback;

/* The longest a removal is waited on: for cookies a page sets back as fast as they are deleted. */
static const ULONGLONG kCookieRemovalLimitMs = 2000;

/*
 * Deletes the cookies [url] gets and reports [requestId] once a read finds none left. A deletion is
 * not in the jar yet when the call that asks for it returns, and nothing says when it is, so a read
 * that comes right behind it can still find the cookie.
 */
static void removeCookiesUntilGone(
    ComPtr<ICoreWebView2CookieManager> cm, std::wstring url, jlong requestId, ULONGLONG deadline) {
    HRESULT started = cm->GetCookies(
        url.c_str(),
        Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [cm, url, requestId, deadline](HRESULT result, ICoreWebView2CookieList *list) -> HRESULT {
                UINT count = 0;
                if (SUCCEEDED(result) && list) list->get_Count(&count);
                if (count == 0 || GetTickCount64() >= deadline) {
                    compose_webview_call_on_cookies_removed(requestId);
                    return S_OK;
                }
                for (UINT i = 0; i < count; i++) {
                    ComPtr<ICoreWebView2Cookie> cookie;
                    if (SUCCEEDED(list->GetValueAtIndex(i, &cookie)) && cookie) {
                        cm->DeleteCookie(cookie.Get());
                    }
                }
                removeCookiesUntilGone(cm, url, requestId, deadline);
                return S_OK;
            }).Get());
    if (FAILED(started)) compose_webview_call_on_cookies_removed(requestId);
}

extern "C" {

JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_windows_WebView2WindowsBridge_nativeGetCookies(
    JNIEnv *env, jclass, jlong handle, jstring url) {
    ComposeWebViewState *s = compose_webview_state_from_handle(handle);
    if (!s || !s->cookieManager || !url) {
        compose_webview_call_on_cookies(handle, "[]");
        return;
    }
    std::wstring wurl = compose_webview_jstring_to_wide(env, url);
    jlong h = handle;
    s->cookieManager->GetCookies(
        wurl.c_str(),
        Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [h](HRESULT result, ICoreWebView2CookieList *list) -> HRESULT {
                if (FAILED(result) || !list) {
                    compose_webview_call_on_cookies(h, "[]");
                    return S_OK;
                }
                UINT count = 0;
                list->get_Count(&count);
                std::ostringstream json;
                json << "[";
                bool first = true;
                for (UINT i = 0; i < count; i++) {
                    ComPtr<ICoreWebView2Cookie> cookie;
                    if (FAILED(list->GetValueAtIndex(i, &cookie)) || !cookie) continue;
                    LPWSTR name = nullptr, value = nullptr, domain = nullptr, path = nullptr;
                    cookie->get_Name(&name);
                    cookie->get_Value(&value);
                    cookie->get_Domain(&domain);
                    cookie->get_Path(&path);
                    BOOL secure = FALSE, httpOnly = FALSE, session = FALSE;
                    cookie->get_IsSecure(&secure);
                    cookie->get_IsHttpOnly(&httpOnly);
                    cookie->get_IsSession(&session);
                    double expires = 0;
                    cookie->get_Expires(&expires);
                    long long expiresMs =
                        session ? 0LL : static_cast<long long>(expires * 1000.0);
                    COREWEBVIEW2_COOKIE_SAME_SITE_KIND sameSite =
                        COREWEBVIEW2_COOKIE_SAME_SITE_KIND_LAX;
                    cookie->get_SameSite(&sameSite);
                    const char *ss = "Lax";
                    if (sameSite == COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE) ss = "None";
                    else if (sameSite == COREWEBVIEW2_COOKIE_SAME_SITE_KIND_STRICT) {
                        ss = "Strict";
                    }

                    std::string n = name ? compose_webview_wide_to_utf8(name) : "";
                    std::string v = value ? compose_webview_wide_to_utf8(value) : "";
                    std::string d = domain ? compose_webview_wide_to_utf8(domain) : "";
                    std::string p = path ? compose_webview_wide_to_utf8(path) : "/";
                    if (name) CoTaskMemFree(name);
                    if (value) CoTaskMemFree(value);
                    if (domain) CoTaskMemFree(domain);
                    if (path) CoTaskMemFree(path);

                    if (!first) json << ",";
                    first = false;
                    json << "{\"name\":\"" << compose_webview_json_escape(n)
                         << "\",\"value\":\"" << compose_webview_json_escape(v)
                         << "\",\"domain\":\"" << compose_webview_json_escape(d)
                         << "\",\"path\":\"" << compose_webview_json_escape(p)
                         << "\",\"secure\":" << (secure ? "true" : "false")
                         << ",\"httpOnly\":" << (httpOnly ? "true" : "false")
                         << ",\"sessionOnly\":" << (session ? "true" : "false")
                         << ",\"expiresDate\":" << expiresMs
                         << ",\"sameSite\":\"" << ss << "\"}";
                }
                json << "]";
                compose_webview_call_on_cookies(h, json.str());
                return S_OK;
            }).Get());
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_windows_WebView2WindowsBridge_nativeSetCookie(
    JNIEnv *env,
    jclass,
    jlong handle,
    jstring name,
    jstring value,
    jstring domain,
    jstring path,
    jboolean secure,
    jboolean httpOnly,
    jlong expiresMs,
    jstring sameSite) {
    ComposeWebViewState *s = compose_webview_state_from_handle(handle);
    if (!s || !s->cookieManager || !name || !value) return;

    std::wstring wname = compose_webview_jstring_to_wide(env, name);
    std::wstring wvalue = compose_webview_jstring_to_wide(env, value);
    std::wstring wdomain = compose_webview_jstring_to_wide(env, domain);
    std::wstring wpath = path ? compose_webview_jstring_to_wide(env, path) : L"/";
    if (wpath.empty()) wpath = L"/";

    ComPtr<ICoreWebView2Cookie> cookie;
    if (FAILED(s->cookieManager->CreateCookie(
            wname.c_str(), wvalue.c_str(),
            wdomain.empty() ? L"" : wdomain.c_str(),
            wpath.c_str(),
            &cookie)) ||
        !cookie) {
        return;
    }
    cookie->put_IsSecure(secure == JNI_TRUE ? TRUE : FALSE);
    cookie->put_IsHttpOnly(httpOnly == JNI_TRUE ? TRUE : FALSE);
    if (expiresMs > 0) {
        cookie->put_Expires(static_cast<double>(expiresMs) / 1000.0);
    }
    if (sameSite) {
        std::wstring ss = compose_webview_jstring_to_wide(env, sameSite);
        COREWEBVIEW2_COOKIE_SAME_SITE_KIND kind =
            COREWEBVIEW2_COOKIE_SAME_SITE_KIND_LAX;
        if (_wcsicmp(ss.c_str(), L"None") == 0) {
            kind = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;
        } else if (_wcsicmp(ss.c_str(), L"Strict") == 0) {
            kind = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_STRICT;
        }
        cookie->put_SameSite(kind);
    }
    s->cookieManager->AddOrUpdateCookie(cookie.Get());
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_windows_WebView2WindowsBridge_nativeRemoveAllCookies(
    JNIEnv *, jclass, jlong handle, jlong requestId) {
    ComposeWebViewState *s = compose_webview_state_from_handle(handle);
    if (s && s->cookieManager) {
        s->cookieManager->DeleteAllCookies();
    }
    /* The cookie manager applies its calls in order, so a later read sees the jar empty. */
    compose_webview_call_on_cookies_removed(requestId);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_webview_web_windows_WebView2WindowsBridge_nativeRemoveCookiesForUrl(
    JNIEnv *env, jclass, jlong handle, jstring url, jlong requestId) {
    ComposeWebViewState *s = compose_webview_state_from_handle(handle);
    if (!s || !s->cookieManager || !url) {
        compose_webview_call_on_cookies_removed(requestId);
        return;
    }
    removeCookiesUntilGone(
        s->cookieManager, compose_webview_jstring_to_wide(env, url), requestId,
        GetTickCount64() + kCookieRemovalLimitMs);
}

}  /* extern "C" */
