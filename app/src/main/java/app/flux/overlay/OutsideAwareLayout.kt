package app.flux.overlay

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of an overlay window. When the window was added with `FLAG_WATCH_OUTSIDE_TOUCH`, Android
 * delivers an `ACTION_OUTSIDE` event whenever the user touches anything outside the window. No
 * coordinates are provided for other apps' windows (and none are needed): it simply means "tapped
 * elsewhere", which collapses an expanded card. The touch itself still reaches the app underneath.
 */
class OutsideAwareLayout(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
