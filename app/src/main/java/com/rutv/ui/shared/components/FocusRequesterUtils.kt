package com.rutv.ui.shared.components

import androidx.compose.ui.focus.FocusRequester
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Compose can throw when a requester is used before its node is attached.
 * Keep focus navigation best-effort and never crash the app.
 */
fun FocusRequester.requestFocusSafely(): Boolean {
    return try {
        // `requestFocus()` is fire-and-forget in our Compose version; use the internal
        // boolean focus result when available so callers can retry reliably.
        val internalFocusMethod = focusInternalMethod
        if (internalFocusMethod != null) {
            (internalFocusMethod.invoke(this) as? Boolean) == true
        } else {
            requestFocus()
            true
        }
    } catch (_: IllegalStateException) {
        false
    } catch (_: InvocationTargetException) {
        false
    } catch (_: IllegalAccessException) {
        false
    }
}

private val focusInternalMethod: Method? by lazy(LazyThreadSafetyMode.NONE) {
    val clazz = FocusRequester::class.java
    val preferredNames = listOf("focus\$ui_release", "focus")
    val allMethods = (clazz.methods.asList() + clazz.declaredMethods.asList())

    preferredNames.asSequence()
        .mapNotNull { preferredName ->
            allMethods.firstOrNull { method ->
                method.name == preferredName &&
                    method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
        }
        .firstOrNull()
        ?.apply {
            runCatching { isAccessible = true }
        }
}
