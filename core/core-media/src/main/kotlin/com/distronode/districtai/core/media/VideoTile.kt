package com.distronode.districtai.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack

/**
 * Draws one remote video track.
 *
 * ⛔ THIS COMPOSABLE LIVES IN THIS MODULE BECAUSE IT IS THE ONLY THING ALLOWED TO OPEN A
 * [VideoTrackHandle]. Everything else about the room crosses into `:app` through [CallEngine] as
 * plain data; a video frame cannot, because rendering it means holding the SDK's track and its
 * renderer view. Putting the tile beside the SDK is what keeps that one exception from becoming a
 * LiveKit dependency in the UI module — the screen composes `VideoTile(participant.videoTrack)` and
 * never learns what is inside.
 *
 * ⛔ `TextureViewRenderer` VIA `AndroidView`, NOT A COMPOSE COMPONENT, AND THAT IS WHAT THE SDK
 * OFFERS RATHER THAN A PREFERENCE. livekit-android 2.28.0 ships exactly two renderers —
 * `io.livekit.android.renderer.TextureViewRenderer` and `SurfaceViewRenderer` — and NO composable
 * of any kind (verified by reading the classes in the published `.aar`). The Compose bindings live
 * in a separate `livekit-android-compose-components` artifact, which this module deliberately does
 * not take: it would pull a second Compose dependency graph and its own theming into the module
 * whose whole purpose is to be the narrow seam in front of the SDK. `TextureView` over
 * `SurfaceView` because a `SurfaceView` punches its own window through the view hierarchy — it
 * ignores clipping, rounded corners and elevation, and cannot be animated or overlapped, all of
 * which a participant grid does.
 *
 * ⛔ WRAPPED IN `key(handle)`, WHICH IS LOAD-BEARING AND EASY TO LOSE. `AndroidView`'s `factory`
 * runs ONCE per composition slot; it is not re-invoked when its captured values change. So when a
 * participant's track is replaced (a camera toggled off and on again produces a new track sid) the
 * slot would keep rendering the OLD renderer, still attached to a dead track, and the tile would
 * freeze on its last frame rather than go black — a failure that looks like a stalled network.
 * `key` forces a new slot, which disposes the old view through `onRelease`.
 *
 * ⚠️ NO SIZE OR SCALING IS SET HERE. The renderer fills what it is given, and the grid decides the
 * shape. A default aspect ratio at this level would have to be wrong for either portrait phones or
 * landscape screen-shares.
 *
 * ⚠️ EVERY LINE HERE IS UNCOVERABLE BY UNIT TEST, like the rest of this module's SDK surface — the
 * renderer needs an EGL context and a real GPU. That is the reason it is this short: the aggregate
 * coverage floor is a ratchet, so logic placed here is logic subtracted from the measured codebase.
 * The DECISIONS that a test could check — which participants get a tile, which get an audio-only
 * placeholder, whether the Companion is filtered out — all live in the consumer's ViewModel.
 */
@Composable
fun VideoTile(handle: VideoTrackHandle, modifier: Modifier = Modifier) {
    key(handle) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureViewRenderer(context).also { renderer ->
                    // ⚠️ Init BEFORE attach: the renderer needs the room's EGL context before it
                    // can accept a frame, and a track that delivers one to an uninitialised
                    // renderer logs and drops it rather than failing loudly.
                    handle.initRenderer(renderer)
                    (handle.sdkTrack as? VideoTrack)?.addRenderer(renderer)
                }
            },
            onRelease = { renderer ->
                // ⛔ BOTH HALVES, IN THIS ORDER. Detaching without releasing leaks an EGL surface
                // per tile — a grid that reshuffles a few times exhausts them — and releasing
                // while the track still holds the sink delivers frames into a freed renderer.
                (handle.sdkTrack as? VideoTrack)?.removeRenderer(renderer)
                renderer.release()
            },
        )
    }
}
