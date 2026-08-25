package org.jellyfin.mobile.player.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class TrickplayHelper(
    private val thumbnailContainer: View,
    private val thumbnailView: AppCompatImageView,
    private val seekBarContainer: View? = null,
    private val chapterNameView: AppCompatTextView? = null,
    private val timeView: AppCompatTextView? = null,
) : KoinComponent {
    private val api: ApiClient by inject()
    private val imageLoader: ImageLoader by inject()
    private val context = thumbnailView.context
    private val handler = Handler(Looper.getMainLooper())
    private var thumbnailDisplayWidth = 0

    private var currentRequest: Disposable? = null
    private var pendingRequest: Runnable? = null
    private var pendingTile = -1
    private var lastDispatchedTile = -1
    private var isScrubbing = false
    private var nextDispatchAt = 0L

    fun onMediaSourceChanged(source: JellyfinMediaSource?) {
        onScrubStop()
        sharedSourceState = null

        val item = source?.item
        val resolvedSourceId = source?.id
        val resolvedMediaSourceId = resolvedSourceId?.toUUIDOrNull()
        val resolvedTrickPlayInfo = item?.trickplay?.get(resolvedSourceId)?.values?.firstOrNull()
        if (item == null || resolvedMediaSourceId == null || resolvedTrickPlayInfo == null) return

        sharedSourceState = TrickplaySourceState(
            trickPlayInfo = resolvedTrickPlayInfo,
            itemId = item.id,
            mediaSourceId = resolvedMediaSourceId,
            durationMs = source.runTime.inWholeMilliseconds,
            chapters = item.chapters,
        )
        updateThumbnailSize(resolvedTrickPlayInfo)
    }

    fun onScrubMove(position: Long) {
        if (!isScrubbing) thumbnailContainer.visibility = View.GONE
        isScrubbing = true

        val sourceState = sharedSourceState ?: return
        if (sourceState.durationMs <= 0) return

        val resolvedTrickPlayInfo = sourceState.trickPlayInfo
        val resolvedItemId = sourceState.itemId
        val resolvedMediaSourceId = sourceState.mediaSourceId
        val resolvedPosition = position.coerceIn(0L, sourceState.durationMs)
        updateThumbnailSize(resolvedTrickPlayInfo)

        // Calculate trickplay tile position and offset based on scrubber position
        val currentTile = resolvedPosition.floorDiv(resolvedTrickPlayInfo.interval).toInt()
        val tileSize = resolvedTrickPlayInfo.tileWidth * resolvedTrickPlayInfo.tileHeight
        val tileOffset = currentTile % tileSize
        val tileIndex = currentTile / tileSize
        val tileOffsetX = tileOffset % resolvedTrickPlayInfo.tileWidth
        val tileOffsetY = tileOffset / resolvedTrickPlayInfo.tileWidth
        val offsetX = tileOffsetX * resolvedTrickPlayInfo.width
        val offsetY = tileOffsetY * resolvedTrickPlayInfo.height

        // Seek-bar previews follow the scrubber. Gesture previews omit seekBarContainer and stay centered.
        seekBarContainer?.let { container ->
            val fraction = resolvedPosition.toFloat() / sourceState.durationMs.toFloat()
            val scrubberX = container.x + fraction * container.width
            val clampMin = container.x
            val clampMax = (container.x + container.width - thumbnailDisplayWidth).coerceAtLeast(clampMin)
            thumbnailContainer.x = (scrubberX - thumbnailDisplayWidth / 2f).coerceIn(clampMin, clampMax)
        }

        // Chapter name and timestamp are only present in the regular seek-bar preview.
        chapterNameView?.let { chapterView ->
            val chapterName = sourceState.chapters
                ?.lastOrNull { it.startPositionTicks <= resolvedPosition * Constants.TICKS_PER_MILLISECOND }
                ?.name
            chapterView.isVisible = !chapterName.isNullOrEmpty()
            if (!chapterName.isNullOrEmpty()) chapterView.text = chapterName
        }
        timeView?.text = formatPositionAsElapsedTime(resolvedPosition)

        // Same tile already pending or already displayed - position updated above, nothing else to do
        if (currentTile == pendingTile || currentTile == lastDispatchedTile) return

        // Cancel previous pending request and schedule a new one for the latest tile
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingTile = currentTile

        val url = api.trickplayApi.getTrickplayTileImageUrl(
            itemId = resolvedItemId,
            width = resolvedTrickPlayInfo.width,
            index = tileIndex,
            mediaSourceId = resolvedMediaSourceId,
        )

        val runnable = Runnable {
            dispatchRequest(
                url = url,
                offsetX = offsetX,
                offsetY = offsetY,
                tileWidth = resolvedTrickPlayInfo.width,
                tileHeight = resolvedTrickPlayInfo.height,
                tile = currentTile,
                mediaSourceId = resolvedMediaSourceId,
            )
        }
        pendingRequest = runnable

        // Run immediately if next dispatch time has passed, otherwise schedule for the remaining time
        handler.postAtTime(
            runnable,
            maxOf(SystemClock.uptimeMillis(), nextDispatchAt),
        )
    }

    private fun updateThumbnailSize(trickPlayInfo: TrickplayInfoDto) {
        val displayHeight = thumbnailView.layoutParams.height.takeIf { it > 0 }
            ?: context.resources.getDimensionPixelSize(R.dimen.trickplay_thumbnail_height)
        val displayWidth = (displayHeight * trickPlayInfo.width.toFloat() / trickPlayInfo.height).roundToInt()
        if (displayWidth == thumbnailDisplayWidth) return

        thumbnailDisplayWidth = displayWidth
        thumbnailView.updateLayoutParams<ViewGroup.LayoutParams> { width = thumbnailDisplayWidth }
    }

    private fun dispatchRequest(
        url: String,
        offsetX: Int,
        offsetY: Int,
        tileWidth: Int,
        tileHeight: Int,
        tile: Int,
        mediaSourceId: UUID,
    ) {
        lastDispatchedTile = tile
        pendingTile = -1
        pendingRequest = null
        nextDispatchAt = SystemClock.uptimeMillis() + Constants.TRICKPLAY_TILE_REFRESH_WINDOW_MS

        currentRequest?.dispose()
        currentRequest = imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .size(Size.ORIGINAL)
                .maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .set(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = api.clientInfo.name,
                                clientVersion = api.clientInfo.version,
                                deviceId = api.deviceInfo.id,
                                deviceName = api.deviceInfo.name,
                                accessToken = api.accessToken,
                            ),
                        )
                        .build(),
                )
                .transformations(SubsetTransformation(offsetX, offsetY, tileWidth, tileHeight))
                .target(
                    onSuccess = { image ->
                        if (isScrubbing && sharedSourceState?.mediaSourceId == mediaSourceId) {
                            thumbnailView.setImageBitmap(image.toBitmap())
                            thumbnailContainer.visibility = View.VISIBLE
                        }
                    },
                )
                .build(),
        )
    }

    fun onScrubStop(hideThumbnail: Boolean = true) {
        isScrubbing = false
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingRequest = null
        pendingTile = -1
        lastDispatchedTile = -1
        nextDispatchAt = 0L
        currentRequest?.dispose()
        currentRequest = null
        if (hideThumbnail) thumbnailContainer.visibility = View.GONE
    }

    private fun formatPositionAsElapsedTime(positionMs: Long): String {
        // Add half a second before truncating to match Media3's time display round-to-nearest behavior
        val roundToNearestThresholdMs = 500L
        return DateUtils.formatElapsedTime((positionMs + roundToNearestThresholdMs).milliseconds.inWholeSeconds)
    }

    private data class TrickplaySourceState(
        val trickPlayInfo: TrickplayInfoDto,
        val itemId: UUID,
        val mediaSourceId: UUID,
        val durationMs: Long,
        val chapters: List<ChapterInfo>?,
    )

    companion object {
        private var sharedSourceState: TrickplaySourceState? = null
    }
}
