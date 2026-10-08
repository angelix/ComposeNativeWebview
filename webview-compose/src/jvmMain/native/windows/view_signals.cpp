#include "compose_webview_internal.h"

using Microsoft::WRL::Callback;

/* True for an http: or https: URL; the scheme is compared case-insensitively. */
static bool isHttpUrl(const std::wstring &url) {
    size_t colon = url.find(L':');
    if (colon == std::wstring::npos) return false;
    std::wstring scheme;
    for (size_t i = 0; i < colon; ++i) {
        wchar_t c = url[i];
        scheme.push_back(c >= L'A' && c <= L'Z' ? static_cast<wchar_t>(c - L'A' + L'a') : c);
    }
    return scheme == L"http" || scheme == L"https";
}

/* True only when the runtime reports that the main frame asked for the new window. A runtime
 * without ICoreWebView2NewWindowRequestedEventArgs3 / ICoreWebView2FrameInfo2 cannot say, so the
 * request counts as not from the main frame. */
static bool requestedByMainFrame(ICoreWebView2NewWindowRequestedEventArgs *args) {
    ComPtr<ICoreWebView2NewWindowRequestedEventArgs3> args3;
    if (FAILED(args->QueryInterface(IID_PPV_ARGS(args3.GetAddressOf()))) || !args3) return false;
    ComPtr<ICoreWebView2FrameInfo> frame;
    if (FAILED(args3->get_OriginalSourceFrameInfo(&frame)) || !frame) return false;
    ComPtr<ICoreWebView2FrameInfo2> frame2;
    if (FAILED(frame.As(&frame2)) || !frame2) return false;
    COREWEBVIEW2_FRAME_KIND kind = COREWEBVIEW2_FRAME_KIND_UNKNOWN;
    return SUCCEEDED(frame2->get_FrameKind(&kind)) && kind == COREWEBVIEW2_FRAME_KIND_MAIN_FRAME;
}

void compose_webview_hook_events(ComposeWebViewState *s) {
    auto *raw = s;

    s->webview->add_NavigationStarting(
        Callback<ICoreWebView2NavigationStartingEventHandler>(
            [raw](ICoreWebView2 *, ICoreWebView2NavigationStartingEventArgs *args) -> HRESULT {
                LPWSTR uri = nullptr;
                std::wstring url;
                if (SUCCEEDED(args->get_Uri(&uri)) && uri) {
                    url.assign(uri);
                    CoTaskMemFree(uri);
                    {
                        std::lock_guard<std::mutex> lock(raw->sourceMutex);
                        raw->lastSource = url;
                    }
                }
                /* Cancel before flipping isLoading so a rejected nav cannot
                 * leave the view stuck in Loading forever. */
                if (!url.empty() &&
                    url.rfind(L"about:", 0) != 0 &&
                    url.rfind(L"data:", 0) != 0 &&
                    url.rfind(L"blob:", 0) != 0) {
                    if (!compose_webview_call_on_navigate(raw->handle, url)) {
                        args->put_Cancel(TRUE);
                        raw->isLoading.store(false, std::memory_order_release);
                        return S_OK;
                    }
                }
                raw->isLoading.store(true, std::memory_order_release);
                return S_OK;
            }).Get(),
        &s->navigationStartingToken);

    s->webview->add_NavigationCompleted(
        Callback<ICoreWebView2NavigationCompletedEventHandler>(
            [raw](ICoreWebView2 *wv, ICoreWebView2NavigationCompletedEventArgs *) -> HRESULT {
                raw->isLoading.store(false, std::memory_order_release);
                LPWSTR src = nullptr;
                if (SUCCEEDED(wv->get_Source(&src)) && src) {
                    std::lock_guard<std::mutex> lock(raw->sourceMutex);
                    raw->lastSource.assign(src);
                    CoTaskMemFree(src);
                }
                LPWSTR title = nullptr;
                if (SUCCEEDED(wv->get_DocumentTitle(&title)) && title) {
                    std::lock_guard<std::mutex> lock(raw->sourceMutex);
                    raw->lastTitle.assign(title);
                    CoTaskMemFree(title);
                }
                BOOL b = FALSE;
                if (SUCCEEDED(wv->get_CanGoBack(&b))) raw->canGoBack.store(b == TRUE);
                if (SUCCEEDED(wv->get_CanGoForward(&b))) raw->canGoForward.store(b == TRUE);
                return S_OK;
            }).Get(),
        &s->navigationCompletedToken);

    s->webview->add_SourceChanged(
        Callback<ICoreWebView2SourceChangedEventHandler>(
            [raw](ICoreWebView2 *wv, ICoreWebView2SourceChangedEventArgs *) -> HRESULT {
                LPWSTR src = nullptr;
                if (SUCCEEDED(wv->get_Source(&src)) && src) {
                    std::lock_guard<std::mutex> lock(raw->sourceMutex);
                    raw->lastSource.assign(src);
                    CoTaskMemFree(src);
                }
                return S_OK;
            }).Get(),
        &s->sourceChangedToken);

    s->webview->add_HistoryChanged(
        Callback<ICoreWebView2HistoryChangedEventHandler>(
            [raw](ICoreWebView2 *wv, IUnknown *) -> HRESULT {
                BOOL b = FALSE;
                if (SUCCEEDED(wv->get_CanGoBack(&b))) raw->canGoBack.store(b == TRUE);
                if (SUCCEEDED(wv->get_CanGoForward(&b))) raw->canGoForward.store(b == TRUE);
                return S_OK;
            }).Get(),
        &s->historyChangedToken);

    s->webview->add_DocumentTitleChanged(
        Callback<ICoreWebView2DocumentTitleChangedEventHandler>(
            [raw](ICoreWebView2 *wv, IUnknown *) -> HRESULT {
                LPWSTR title = nullptr;
                if (SUCCEEDED(wv->get_DocumentTitle(&title)) && title) {
                    {
                        std::lock_guard<std::mutex> lock(raw->sourceMutex);
                        raw->lastTitle.assign(title);
                    }
                    CoTaskMemFree(title);
                    /* Fallback ready-signal: some Navigate(data:) paths paint
                     * and expose a title before NavigationCompleted is seen by
                     * the host message loop. Clear isLoading so Kotlin can
                     * transition to Finished and inject the JS bridge. */
                    if (!raw->lastTitle.empty()) {
                        raw->isLoading.store(false, std::memory_order_release);
                    }
                }
                return S_OK;
            }).Get(),
        &s->documentTitleChangedToken);

    s->compController->add_CursorChanged(
        Callback<ICoreWebView2CursorChangedEventHandler>(
            [raw](ICoreWebView2CompositionController *cc, IUnknown *) -> HRESULT {
                HCURSOR cursor = nullptr;
                if (SUCCEEDED(cc->get_Cursor(&cursor))) {
                    raw->currentCursor.store(cursor, std::memory_order_release);
                    POINT pt;
                    if (GetCursorPos(&pt) && IsWindow(raw->parent)) {
                        ScreenToClient(raw->parent, &pt);
                        if (compose_webview_inside(raw, pt.x, pt.y)) SetCursor(cursor);
                    }
                }
                return S_OK;
            }).Get(),
        &s->cursorChangedToken);

    s->webview->add_ContentLoading(
        Callback<ICoreWebView2ContentLoadingEventHandler>(
            [raw](ICoreWebView2 *, ICoreWebView2ContentLoadingEventArgs *) -> HRESULT {
                raw->documentGeneration.fetch_add(1, std::memory_order_acq_rel);
                return S_OK;
            }).Get(),
        &s->contentLoadingToken);

    s->webview->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [raw](ICoreWebView2 *, ICoreWebView2WebMessageReceivedEventArgs *args) -> HRESULT {
                LPWSTR msg = nullptr;
                if (SUCCEEDED(args->TryGetWebMessageAsString(&msg)) && msg) {
                    std::wstring message(msg);
                    CoTaskMemFree(msg);
                    if (raw->channelEnabled) {
                        LPWSTR src = nullptr;
                        std::wstring source;
                        if (SUCCEEDED(args->get_Source(&src)) && src) {
                            source.assign(src);
                            CoTaskMemFree(src);
                        }
                        compose_webview_call_on_sourced_message(
                            raw->handle,
                            raw->documentGeneration.load(std::memory_order_acquire),
                            source,
                            message);
                    } else {
                        compose_webview_call_on_ipc(raw->handle, compose_webview_wide_to_utf8(message));
                    }
                } else {
                    LPWSTR json = nullptr;
                    if (SUCCEEDED(args->get_WebMessageAsJson(&json)) && json) {
                        compose_webview_call_on_ipc(
                            raw->handle, compose_webview_wide_to_utf8(json));
                        CoTaskMemFree(json);
                    }
                }
                return S_OK;
            }).Get(),
        &s->webMessageToken);

    /* Camera and microphone are denied; every other permission kind keeps WebView2's default. */
    s->webview->add_PermissionRequested(
        Callback<ICoreWebView2PermissionRequestedEventHandler>(
            [](ICoreWebView2 *, ICoreWebView2PermissionRequestedEventArgs *args) -> HRESULT {
                COREWEBVIEW2_PERMISSION_KIND kind = COREWEBVIEW2_PERMISSION_KIND_UNKNOWN_PERMISSION;
                if (SUCCEEDED(args->get_PermissionKind(&kind)) &&
                    (kind == COREWEBVIEW2_PERMISSION_KIND_CAMERA ||
                     kind == COREWEBVIEW2_PERMISSION_KIND_MICROPHONE)) {
                    args->put_State(COREWEBVIEW2_PERMISSION_STATE_DENY);
                }
                return S_OK;
            }).Get(),
        &s->permissionRequestedToken);

    /* Never a second window. A popup (window.open, target=_blank) loads in this view only when the
     * main frame asked for an http(s) URL; a sub-frame (possibly cross-origin) must not navigate the
     * host's view, and about:blank, javascript:, file:, data: and blob: popups are ignored. */
    s->webview->add_NewWindowRequested(
        Callback<ICoreWebView2NewWindowRequestedEventHandler>(
            [](ICoreWebView2 *wv, ICoreWebView2NewWindowRequestedEventArgs *args) -> HRESULT {
                args->put_Handled(TRUE);
                if (!requestedByMainFrame(args)) return S_OK;
                LPWSTR uri = nullptr;
                if (SUCCEEDED(args->get_Uri(&uri)) && uri) {
                    std::wstring url(uri);
                    CoTaskMemFree(uri);
                    if (isHttpUrl(url)) wv->Navigate(url.c_str());
                }
                return S_OK;
            }).Get(),
        &s->newWindowRequestedToken);
}

void compose_webview_unhook_events(ComposeWebViewState *s) {
    if (!s) return;
    if (s->webview) {
        s->webview->remove_NavigationStarting(s->navigationStartingToken);
        s->webview->remove_NavigationCompleted(s->navigationCompletedToken);
        s->webview->remove_SourceChanged(s->sourceChangedToken);
        s->webview->remove_HistoryChanged(s->historyChangedToken);
        s->webview->remove_DocumentTitleChanged(s->documentTitleChangedToken);
        s->webview->remove_WebMessageReceived(s->webMessageToken);
        s->webview->remove_ContentLoading(s->contentLoadingToken);
        s->webview->remove_PermissionRequested(s->permissionRequestedToken);
        s->webview->remove_NewWindowRequested(s->newWindowRequestedToken);
    }
    if (s->compController) {
        s->compController->remove_CursorChanged(s->cursorChangedToken);
    }
}
