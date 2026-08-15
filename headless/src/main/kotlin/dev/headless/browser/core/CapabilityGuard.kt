package dev.headless.browser.core

import dev.headless.browser.Capabilities
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError

/**
 * Enforces guard checks against probed device [Capabilities].
 *
 * Rules:
 * - If a probed capability is `false`, invoking the API method immediately throws
 *   [ErrorCode.UNSUPPORTED] rather than waiting or timing out.
 * - If a probed capability is `true`, the operation is guaranteed to proceed.
 */
internal object CapabilityGuard {

    fun requireDocumentStartScript(capabilities: Capabilities) {
        if (!capabilities.documentStartScript) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "addInitScript (DOCUMENT_START_SCRIPT) is not supported on this device's WebView package",
            )
        }
    }

    fun requireWebMessageChannel(capabilities: Capabilities) {
        if (!capabilities.webMessageChannel) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "WebMessageChannel (WEB_MESSAGE_LISTENER) is not supported on this device's WebView package",
            )
        }
    }

    fun requireServiceWorkerInterception(capabilities: Capabilities) {
        if (!capabilities.serviceWorkerInterception) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Service worker interception is not supported on this device's WebView package",
            )
        }
    }

    fun requireRendererResponsiveness(capabilities: Capabilities) {
        if (!capabilities.rendererResponsiveness) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Renderer responsiveness monitoring is not supported on this device's WebView package",
            )
        }
    }

    fun requireScreenshots(capabilities: Capabilities) {
        if (!capabilities.screenshots) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Screenshot requires a viewport-sized session; current view is detached 1x1",
            )
        }
    }
}
