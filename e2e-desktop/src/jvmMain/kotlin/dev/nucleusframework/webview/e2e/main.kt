package dev.nucleusframework.webview.e2e

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.webview.e2e.visualsuite.VisualSuiteApp
import dev.nucleusframework.window.TitleBar
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** The suite finishes in about a minute; this long without finishing, it is stuck. */
private const val SUITE_TIMEOUT_MINUTES = 15L

/**
 * Full visual e2e suite entrypoint.
 *
 * Runs a real Tao window + desktop WebView (WebKit2GTK / WKWebView / WebView2)
 * and exercises the full desktop API. Writes a report under java.io.tmpdir
 * (or `$COMPOSEWEBVIEW_SUITE_REPORT`) and exits 0/1.
 *
 *   ./gradlew :e2e-desktop:run
 *
 * Exit code 1 means the suite reported failures (scroll up for the report),
 * or the native backend failed to load. Exit code 2 means a thread ended in an
 * exception nothing caught, 3 that the suite got stuck.
 */
fun main() {
    // The window's event loop ending in an exception leaves the process running, with nothing more
    // to say. End the run instead, here and when the suite stops making progress.
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        System.err.println("Visual e2e: uncaught on \"${thread.name}\"")
        exception.printStackTrace()
        Runtime.getRuntime().halt(2)
    }
    thread(isDaemon = true, name = "suite-watchdog") {
        Thread.sleep(SUITE_TIMEOUT_MINUTES * 60_000)
        System.err.println("Visual e2e STUCK after $SUITE_TIMEOUT_MINUTES minutes. Threads:")
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            System.err.println("\"${thread.name}\" ${thread.state}")
            stack.forEach { System.err.println("\tat $it") }
        }
        Runtime.getRuntime().halt(3)
    }
    var exitCode = 1
    nucleusApplication(backend = NucleusBackend.Tao) {
        val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
        DecoratedWindow(
            onCloseRequest = {
                exitApplication()
                exitProcess(exitCode)
            },
            state = windowState,
            title = "ComposeNativeWebView — Visual E2E Suite",
        ) {
            TitleBar { }
            VisualSuiteApp { passed, reportPath ->
                println("SUITE_FINISHED passed=$passed report=$reportPath")
                if (!passed) {
                    System.err.println(
                        "Visual e2e FAILED — open report: $reportPath\n" +
                            "If every case failed early, build natives: " +
                            "./gradlew :webview-compose:buildNativeWindows  (or buildNativeLinux)",
                    )
                }
                exitCode = if (passed) 0 else 1
                exitApplication()
                // Ensure process exit for CI / agent validation
                exitProcess(exitCode)
            }
        }
    }
}
