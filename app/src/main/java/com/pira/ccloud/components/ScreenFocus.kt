package com.pira.ccloud.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

// Where D-pad focus goes when a screen is shown. Whenever the focused element leaves the screen,
// as it does when the user opens another screen or goes back, Android gives focus to the element
// at the top left: the sidebar's Movies item. So every navigation destination is wrapped in
// ScreenFocus, which moves focus into the screen itself.

private class ScreenFocusState(lastFocusedKey: MutableState<Any?>) {
    /** Key of the [restorableFocus] element that had focus last. */
    var lastFocusedKey by lastFocusedKey
    val restorable = mutableStateMapOf<Any, FocusRequester>()
    var initial by mutableStateOf<FocusRequester?>(null)

    val target: FocusRequester?
        get() = lastFocusedKey?.let { restorable[it] } ?: initial
}

private val LocalScreenFocus = staticCompositionLocalOf<ScreenFocusState?> { null }
private val LocalKeyPresses = staticCompositionLocalOf<MutableIntState?> { null }

/**
 * Wraps the app's UI, sidebar included, to count key presses: a [ScreenFocus] whose content is
 * still loading must not move focus once the user has started moving it.
 */
@Composable
fun ScreenFocusHost(content: @Composable () -> Unit) {
    val keyPresses = remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalKeyPresses provides keyPresses) {
        Box(
            Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) keyPresses.intValue++
                false
            }
        ) {
            content()
        }
    }
}

/**
 * Moves D-pad focus into [content], a navigation destination, when it is shown while the user
 * navigates with a remote, or when the user switches to the remote (e.g. after using an air
 * mouse): to the [restorableFocus] element that had focus when the user left the screen (e.g. the
 * movie they opened, when they come back), or else to the [initialFocus] element.
 */
@Composable
fun ScreenFocus(content: @Composable () -> Unit) {
    // Saved while the screen is in the back stack
    val lastFocusedKey = rememberSaveable { mutableStateOf<Any?>(null) }
    val state = remember { ScreenFocusState(lastFocusedKey) }
    val inputMode = LocalInputModeManager.current.inputMode
    val keyPresses = LocalKeyPresses.current
    var hasFocus by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalScreenFocus provides state) {
        Box(Modifier.onFocusChanged { hasFocus = it.hasFocus }) {
            content()
        }
    }

    LaunchedEffect(inputMode) {
        // With touch or a mouse, nothing should look focused. And when the user switches to the
        // remote while something in the screen still has focus, they carry on from there.
        if (inputMode != InputMode.Keyboard || hasFocus) return@LaunchedEffect
        val keyPressesAtStart = keyPresses?.intValue
        suspend fun awaitTarget() = snapshotFlow { state.target }.filterNotNull().first()

        // Wait for the content, which may still be loading, and until the target stays the same
        // for a couple of frames: at first it can change, e.g. when content loaded from storage
        // replaces a placeholder, or when a list's items are added
        var target = awaitTarget()
        while (true) {
            repeat(2) { withFrameNanos { } }
            val current = state.target
            if (current === target) break
            target = current ?: awaitTarget()
        }
        if (keyPresses?.intValue != keyPressesAtStart) return@LaunchedEffect
        try {
            target.requestFocus()
        } catch (e: IllegalStateException) {
            // The target has just left the screen
        }
    }
}

/**
 * Focus goes to this element when its screen is shown, unless it can return to a
 * [restorableFocus] element. On a list or grid, focus goes to its top-left visible item.
 */
fun Modifier.initialFocus(enabled: Boolean = true): Modifier = composed {
    val state = LocalScreenFocus.current
    if (state == null || !enabled) return@composed Modifier
    val requester = remember { FocusRequester() }
    DisposableEffect(state, requester) {
        state.initial = requester
        onDispose { if (state.initial === requester) state.initial = null }
    }
    focusRequester(requester)
}

/**
 * Focus returns to this element when its screen is shown again, e.g. after going back to it, if
 * it had focus last among the screen's restorable elements. [key] identifies the element on its
 * screen and must be saveable in a Bundle (e.g. an Int or a String).
 */
fun Modifier.restorableFocus(key: Any): Modifier = composed {
    val state = LocalScreenFocus.current ?: return@composed Modifier
    val requester = remember { FocusRequester() }
    DisposableEffect(state, key, requester) {
        state.restorable[key] = requester
        onDispose { if (state.restorable[key] === requester) state.restorable.remove(key) }
    }
    focusRequester(requester).onFocusChanged { if (it.isFocused) state.lastFocusedKey = key }
}
