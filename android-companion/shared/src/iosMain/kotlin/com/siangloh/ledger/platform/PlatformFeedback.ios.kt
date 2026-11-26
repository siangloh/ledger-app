package com.siangloh.ledger.platform

import androidx.compose.runtime.Composable
import com.siangloh.ledger.core.prefs.PlatformPrefs
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.preferredLanguages
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun showPlatformToast(message: String) {
    println("LedgerToast: $message")
    PlatformToastBus.emit(message)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun sharePlatformTextFile(fileName: String, mimeType: String, content: String): Boolean {
    return try {
        val safeName = fileName.replace('/', '_').replace('\\', '_').ifBlank { "export.txt" }
        val tempDir = NSTemporaryDirectory()
        val filePath = if (tempDir.endsWith("/")) "$tempDir$safeName" else "$tempDir/$safeName"
        val bytes = content.encodeToByteArray()
        val nsData = bytes.usePinned { pinned ->
            if (bytes.isEmpty()) {
                NSData.create()
            } else {
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        }
        val written = nsData.writeToFile(filePath, atomically = true)
        if (!written) {
            println("sharePlatformTextFile: failed to write $filePath")
            return false
        }
        val fileUrl = NSURL.fileURLWithPath(filePath)
        dispatch_async(dispatch_get_main_queue()) {
            try {
                val topController = findTopViewController()
                if (topController == null) {
                    println("sharePlatformTextFile: no active UIViewController to present share sheet")
                    return@dispatch_async
                }
                val activityVC = UIActivityViewController(
                    activityItems = listOf(fileUrl),
                    applicationActivities = null
                )
                activityVC.popoverPresentationController?.apply {
                    sourceView = topController.view
                    sourceRect = CGRectMake(0.0, 0.0, 1.0, 1.0)
                }
                topController.presentViewController(activityVC, animated = true, completion = null)
            } catch (e: Throwable) {
                println("sharePlatformTextFile present failed: ${e.message}")
            }
        }
        true
    } catch (e: Throwable) {
        println("sharePlatformTextFile failed: ${e.message}")
        false
    }
}

private fun findTopViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
    val activeScene = scenes.firstOrNull {
        it.activationState == UISceneActivationStateForegroundActive
    } ?: scenes.firstOrNull() ?: return null

    val windows = activeScene.windows.filterIsInstance<UIWindow>()
    val keyWindow = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: return null
    var top = keyWindow.rootViewController ?: return null
    while (top.presentedViewController != null) {
        top = top.presentedViewController!!
    }
    return top
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val registrar = LocalPlatformBackHandlerRegistrar.current
    registrar?.invoke(enabled, onBack)
}

actual object PlatformLocaleBridge {
    private const val PREFS_NAME = "ledger_lang_prefs"
    private const val KEY_LANG = "app_lang"

    private val prefs by lazy { PlatformPrefs(PREFS_NAME) }

    actual fun getCurrentLanguage(): String {
        val saved = prefs.getString(KEY_LANG, null)
        if (!saved.isNullOrBlank()) {
            return LocaleStateHolder.normalizeLanguage(saved)
        }
        val systemPreferred = NSLocale.preferredLanguages.firstOrNull()?.toString()
        return if (!systemPreferred.isNullOrBlank()) {
            LocaleStateHolder.normalizeLanguage(systemPreferred)
        } else {
            LocaleStateHolder.DEFAULT_LANGUAGE
        }
    }

    actual fun setLanguage(lang: String) {
        val normalized = LocaleStateHolder.normalizeLanguage(lang)
        prefs.putString(KEY_LANG, normalized)
    }
}
