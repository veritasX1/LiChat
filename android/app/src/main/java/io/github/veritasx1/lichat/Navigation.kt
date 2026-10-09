package io.github.veritasx1.lichat

import android.provider.Settings as AndroidSettings
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.cancellation.CancellationException

/** How one page follows another (Li-Gestaltung „Übergänge“, card a35a239e). */
enum class Move { Forward, Back, SheetUp, SheetDown, Fade }

/** Where „Zurück“ leads from a page; null on the list. */
fun parent(page: Page): Page? = when (page) {
    Page.List -> null
    is Page.Chat, Page.New, Page.Settings -> Page.List
    is Page.Info -> Page.Chat(page.cid)
}

private fun depth(page: Page): Int = parent(page)?.let { depth(it) + 1 } ?: 0

fun move(from: Page, to: Page, reduceMotion: Boolean = false): Move = when {
    reduceMotion -> Move.Fade
    to == Page.New -> Move.SheetUp
    from == Page.New -> Move.SheetDown
    depth(to) > depth(from) -> Move.Forward
    depth(to) < depth(from) -> Move.Back
    else -> Move.Fade
}

private const val MS = 260

private fun spec(move: Move): ContentTransform = when (move) {
    // Like iOS: the new page comes in from the right, the old one moves a third to the left.
    Move.Forward -> slideInHorizontally(tween(MS)) { it } togetherWith slideOutHorizontally(tween(MS)) { -it / 3 }
    Move.Back -> slideInHorizontally(tween(MS)) { -it / 3 } togetherWith slideOutHorizontally(tween(MS)) { it }
    // Sheets rise from the bottom and go back down; the page below stays put.
    Move.SheetUp -> slideInVertically(tween(MS)) { it } togetherWith fadeOut(tween(MS, delayMillis = MS))
    Move.SheetDown -> fadeIn(tween(0)) togetherWith slideOutVertically(tween(MS)) { it }
    Move.Fade -> fadeIn(tween(150)) togetherWith fadeOut(tween(150))
}

/**
 * Shows [page] and animates every change in the direction it goes. Back (key, button and the edge
 * swipe) follows the finger: the predictive back gesture seeks the transition, letting go finishes it,
 * cancelling springs back. With „Animationen entfernen“ (animator scale 0) pages only cross-fade.
 */
@OptIn(ExperimentalTransitionApi::class)
@Composable
fun Navigation(page: Page, onBack: (Page) -> Unit, content: @Composable (Page) -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val state = remember { SeekableTransitionState(page) }
    LaunchedEffect(page) { if (state.currentState != page || state.targetState != page) state.animateTo(page) }
    val back = parent(page)
    PredictiveBackHandler(enabled = back != null) { progress ->
        val target = back ?: return@PredictiveBackHandler
        try {
            progress.collect { event -> state.seekTo(event.progress, target) }
            onBack(target)
        } catch (e: CancellationException) {
            state.animateTo(page)
            throw e
        }
    }
    val transition = rememberTransition(state, label = "navigation")
    transition.AnimatedContent(
        transitionSpec = { spec(move(initialState, targetState, reduceMotion)) },
    ) { current -> content(current) }
}
