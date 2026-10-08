#include "compose_webview_internal.h"

@implementation ComposeWebViewState (Signals)

- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message
{
    (void)userContentController;
    if (![message.name isEqualToString:@"ipc"]) return;

    NSString *payload = nil;
    id body = message.body;
    if ([body isKindOfClass:[NSString class]]) {
        payload = (NSString *)body;
    } else if ([body isKindOfClass:[NSDictionary class]] ||
               [body isKindOfClass:[NSArray class]] ||
               [body isKindOfClass:[NSNumber class]]) {
        NSError *err = nil;
        NSData *data = [NSJSONSerialization dataWithJSONObject:body options:0 error:&err];
        if (data != nil && err == nil) {
            payload = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        }
    }
    if (payload == nil) return;

    JNIEnv *env = compose_webview_get_env();
    if (env == NULL) return;
    compose_webview_ensure_bridge_methods(env);
    if (compose_webview_bridge_class() == NULL || compose_webview_on_ipc() == NULL) return;

    jstring jmsg = compose_webview_ns_to_jstring(env, payload);
    (*env)->CallStaticVoidMethod(
        env, compose_webview_bridge_class(), compose_webview_on_ipc(), self.handle, jmsg);
    if (jmsg != NULL) (*env)->DeleteLocalRef(env, jmsg);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message
                 replyHandler:(void (^)(id _Nullable, NSString * _Nullable))replyHandler
{
    (void)userContentController;
    if (self.channelName == nil || ![message.name isEqualToString:self.channelName] ||
        ![message.body isKindOfClass:[NSString class]]) {
        replyHandler(nil, @"Unsupported message");
        return;
    }
    WKFrameInfo *frame = message.frameInfo;
    WKSecurityOrigin *o = frame.securityOrigin;
    NSString *origin = @"null";
    if (o.host.length > 0 && ([o.protocol isEqualToString:@"https"] || [o.protocol isEqualToString:@"http"])) {
        BOOL defaultPort = o.port == 0 ||
            ([o.protocol isEqualToString:@"https"] && o.port == 443) ||
            ([o.protocol isEqualToString:@"http"] && o.port == 80);
        origin = defaultPort
            ? [NSString stringWithFormat:@"%@://%@", o.protocol, o.host.lowercaseString]
            : [NSString stringWithFormat:@"%@://%@:%ld", o.protocol, o.host.lowercaseString, (long)o.port];
    }

    JNIEnv *env = compose_webview_get_env();
    if (env == NULL) {
        replyHandler(nil, @"Host unavailable");
        return;
    }
    compose_webview_ensure_bridge_methods(env);
    if (compose_webview_bridge_class() == NULL || compose_webview_on_channel_message() == NULL) {
        replyHandler(nil, @"Host unavailable");
        return;
    }

    jlong replyId = ++self.nextReplyId;
    self.pendingReplies[@(replyId)] = [replyHandler copy];

    jstring jorigin = compose_webview_ns_to_jstring(env, origin);
    jstring jbody = compose_webview_ns_to_jstring(env, (NSString *)message.body);
    (*env)->CallStaticVoidMethod(
        env, compose_webview_bridge_class(), compose_webview_on_channel_message(),
        self.handle, replyId, jorigin, frame.isMainFrame ? JNI_TRUE : JNI_FALSE, jbody);
    if (jorigin != NULL) (*env)->DeleteLocalRef(env, jorigin);
    if (jbody != NULL) (*env)->DeleteLocalRef(env, jbody);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

- (void)webView:(WKWebView *)webView
decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler
{
    (void)webView;
    NSURL *url = navigationAction.request.URL;
    NSString *uri = url.absoluteString;
    if (uri == nil || uri.length == 0) {
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }

    if ([uri hasPrefix:@"about:"] || [uri hasPrefix:@"data:"] || [uri hasPrefix:@"blob:"]) {
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }

    JNIEnv *env = compose_webview_get_env();
    if (env == NULL) {
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }
    compose_webview_ensure_bridge_methods(env);
    if (compose_webview_bridge_class() == NULL || compose_webview_on_navigate() == NULL) {
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }

    jstring juri = compose_webview_ns_to_jstring(env, uri);
    jboolean allow = (*env)->CallStaticBooleanMethod(
        env, compose_webview_bridge_class(), compose_webview_on_navigate(), self.handle, juri);
    if (juri != NULL) (*env)->DeleteLocalRef(env, juri);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        decisionHandler(WKNavigationActionPolicyAllow);
        return;
    }
    decisionHandler(allow ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}

@end
