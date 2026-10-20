package com.simplemobiletools.gallery.pro.fragments

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.activities.PanoramaVideoActivity
import com.simplemobiletools.gallery.pro.activities.VideoActivity
import com.simplemobiletools.gallery.pro.databinding.PagerVideoItemBinding
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.extensions.hasNavBar
import com.simplemobiletools.gallery.pro.extensions.parseFileChannel
import com.simplemobiletools.gallery.pro.helpers.*
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.views.MediaSideScroll
import java.io.File
import java.io.FileInputStream

@UnstableApi
class VideoFragment : ViewPagerFragment(), SeekBar.OnSeekBarChangeListener {
    private val PROGRESS = "progress"

    private var mIsFullscreen = false
    private var mWasFragmentInit = false
    private var mIsPanorama = false
    private var mIsFragmentVisible = false
    private var mIsDragged = false
    private var mWasVideoStarted = false
    private var mWasPlayerInited = false
    private var mWasLastPositionRestored = false
    private var mPlayOnPrepared = false
    private var mIsPlayerPrepared = false
    private var mCurrTime = 0
    private var mDuration = 0
    private var mPositionWhenInit = 0
    private var mPositionAtPause = 0L
    private var mIsHdrVideo = false
    private var mIsHdrColorModeActive = false
    var mIsPlaying = false

    private var mExoPlayer: ExoPlayer? = null
    private var mVideoSize = Point(1, 1)
    private var mTimerHandler = Handler()

    private var mStoredShowExtendedDetails = false
    private var mStoredHideExtendedDetails = false
    private var mStoredBottomActions = true
    private var mStoredExtendedDetails = 0
    private var mStoredRememberLastVideoPosition = false

    private lateinit var mTimeHolder: View
    private lateinit var mBrightnessSideScroll: MediaSideScroll
    private lateinit var mVolumeSideScroll: MediaSideScroll
    private lateinit var binding: PagerVideoItemBinding
    private lateinit var mView: View
    private lateinit var mMedium: Medium
    private lateinit var mConfig: Config
    private lateinit var mTextureView: TextureView
    private lateinit var mHdrSurfaceView: SurfaceView
    private lateinit var mVideoSurfaceContainer: FrameLayout
    private lateinit var mCurrTimeView: TextView
    private lateinit var mPlayPauseButton: ImageView
    private lateinit var mSeekBar: SeekBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val activity = requireActivity()
        val arguments = requireArguments()

        mMedium = arguments.getSerializable(MEDIUM) as Medium
        mConfig = context.config
        binding = PagerVideoItemBinding.inflate(inflater, container, false).apply {
            panoramaOutline.setOnClickListener { openPanorama() }
            bottomVideoTimeHolder.videoCurrTime.setOnClickListener { skip(false) }
            bottomVideoTimeHolder.videoDuration.setOnClickListener { skip(true) }
            videoHolder.setOnClickListener { toggleFullscreen() }
            videoPreview.setOnClickListener { toggleFullscreen() }
            videoSurfaceFrame.controller.settings.swallowDoubleTaps = true

            videoPlayOutline.setOnClickListener {
                if (mConfig.openVideosOnSeparateScreen) {
                    launchVideoPlayer()
                } else {
                    togglePlayPause()
                }
            }

            mPlayPauseButton = bottomVideoTimeHolder.videoTogglePlayPause
            mPlayPauseButton.setOnClickListener {
                togglePlayPause()
            }

            mSeekBar = bottomVideoTimeHolder.videoSeekbar
            mSeekBar.setOnSeekBarChangeListener(this@VideoFragment)
            // adding an empty click listener just to avoid ripple animation at toggling fullscreen
            mSeekBar.setOnClickListener { }

            mTimeHolder = bottomVideoTimeHolder.videoTimeHolder
            mCurrTimeView = bottomVideoTimeHolder.videoCurrTime
            mBrightnessSideScroll = videoBrightnessController
            mVolumeSideScroll = videoVolumeController
            mTextureView = videoSurface
            mHdrSurfaceView = videoHdrSurface
            mVideoSurfaceContainer = videoSurfaceContainer

            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!mConfig.allowInstantChange) {
                        toggleFullscreen()
                        return true
                    }

                    val viewWidth = root.width
                    val instantWidth = viewWidth / 7
                    val clickedX = e.rawX
                    when {
                        clickedX <= instantWidth -> listener?.goToPrevItem()
                        clickedX >= viewWidth - instantWidth -> listener?.goToNextItem()
                        else -> toggleFullscreen()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap(e.rawX)
                    return true
                }
            })

            videoPreview.setOnTouchListener { view, event ->
                handleEvent(event)
                false
            }

            videoSurfaceFrame.setOnTouchListener { view, event ->
                if (videoSurfaceFrame.controller.state.zoom == 1f) {
                    handleEvent(event)
                }

                gestureDetector.onTouchEvent(event)
                false
            }
        }
        mView = binding.root

        if (!arguments.getBoolean(SHOULD_INIT_FRAGMENT, true)) {
            return mView
        }

        storeStateVariables()
        Glide.with(context).load(mMedium.path).into(binding.videoPreview)

        // setMenuVisibility is not called at VideoActivity (third party intent)
        if (!mIsFragmentVisible && activity is VideoActivity) {
            mIsFragmentVisible = true
        }

        mIsFullscreen = activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == View.SYSTEM_UI_FLAG_FULLSCREEN
        initTimeHolder()
        checkIfPanorama()

        ensureBackgroundThread {
            activity.getVideoResolution(mMedium.path)?.apply {
                mVideoSize.x = x
                mVideoSize.y = y
            }
        }

        if (mIsPanorama) {
            binding.apply {
                panoramaOutline.beVisible()
                videoPlayOutline.beGone()
                mVolumeSideScroll.beGone()
                mBrightnessSideScroll.beGone()
                Glide.with(context).load(mMedium.path).into(videoPreview)
            }
        }

        if (!mIsPanorama) {
            if (savedInstanceState != null) {
                mCurrTime = savedInstanceState.getInt(PROGRESS)
            }

            mWasFragmentInit = true
            setVideoSize()

            binding.apply {
                mBrightnessSideScroll.initialize(activity, slideInfo, true, container, singleTap = { x, y ->
                    if (mConfig.allowInstantChange) {
                        listener?.goToPrevItem()
                    } else {
                        toggleFullscreen()
                    }
                }, doubleTap = { x, y ->
                    doSkip(false)
                })

                mVolumeSideScroll.initialize(activity, slideInfo, false, container, singleTap = { x, y ->
                    if (mConfig.allowInstantChange) {
                        listener?.goToNextItem()
                    } else {
                        toggleFullscreen()
                    }
                }, doubleTap = { x, y ->
                    doSkip(true)
                })

                videoSurfaceContainer.onGlobalLayout {
                    if (mIsFragmentVisible && mConfig.autoplayVideos && !mConfig.openVideosOnSeparateScreen) {
                        playVideo()
                    }
                }
            }
        }

        setupVideoDuration()
        checkIfHdrVideo()
        if (mStoredRememberLastVideoPosition) {
            restoreLastVideoSavedPosition()
        }

        return mView
    }

    override fun onResume() {
        super.onResume()
        mConfig = requireContext().config      // make sure we get a new config, in case the user changed something in the app settings
        requireActivity().updateTextColors(binding.videoHolder)
        val allowVideoGestures = mConfig.allowVideoGestures
        updateVideoSurfaceVisibility()
        if (mIsFragmentVisible && requiresHdrColorMode()) {
            // another page or the system may have reset the display mode while we were away
            setWindowHdrColorMode(true)
        }

        mVolumeSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)
        mBrightnessSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)

        checkExtendedDetails()
        initTimeHolder()
        storeStateVariables()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        pauseVideo()
        if (mStoredRememberLastVideoPosition && mIsFragmentVisible && mWasVideoStarted) {
            saveVideoProgress()
        }
        if (mIsHdrColorModeActive) {
            setWindowHdrColorMode(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activity?.isChangingConfigurations == false) {
            cleanup()
        }
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (mIsFragmentVisible && !menuVisible) {
            pauseVideo()
        }

        mIsFragmentVisible = menuVisible
        if (requiresHdrColorMode() || mIsHdrColorModeActive) {
            setWindowHdrColorMode(menuVisible && requiresHdrColorMode())
        }
        if (mWasFragmentInit && menuVisible && mConfig.autoplayVideos && !mConfig.openVideosOnSeparateScreen) {
            playVideo()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setVideoSize()
        initTimeHolder()
        checkExtendedDetails()
        binding.videoSurfaceFrame.onGlobalLayout {
            binding.videoSurfaceFrame.controller.resetState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(PROGRESS, mCurrTime)
    }

    private fun storeStateVariables() {
        mConfig.apply {
            mStoredShowExtendedDetails = showExtendedDetails
            mStoredHideExtendedDetails = hideExtendedDetails
            mStoredExtendedDetails = extendedDetails
            mStoredBottomActions = bottomActions
            mStoredRememberLastVideoPosition = rememberLastVideoPosition
        }
    }

    private fun saveVideoProgress() {
        if (!videoEnded()) {
            if (mExoPlayer != null) {
                mConfig.saveLastVideoPosition(mMedium.path, mExoPlayer!!.currentPosition.toInt() / 1000)
            } else {
                mConfig.saveLastVideoPosition(mMedium.path, mPositionAtPause.toInt() / 1000)
            }
        }
    }

    private fun restoreLastVideoSavedPosition() {
        val pos = mConfig.getLastVideoPosition(mMedium.path)
        if (pos > 0) {
            mPositionAtPause = pos * 1000L
            setPosition(pos)
        }
    }

    private fun setupTimeHolder() {
        mSeekBar.max = mDuration
        binding.bottomVideoTimeHolder.videoDuration.text = mDuration.getFormattedDuration()
        setupTimer()
    }

    private fun setupTimer() {
        activity?.runOnUiThread(object : Runnable {
            override fun run() {
                if (mExoPlayer != null && !mIsDragged && mIsPlaying) {
                    mCurrTime = (mExoPlayer!!.currentPosition / 1000).toInt()
                    mSeekBar.progress = mCurrTime
                    mCurrTimeView.text = mCurrTime.getFormattedDuration()
                }

                mTimerHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun initExoPlayer() {
        if (activity == null || mConfig.openVideosOnSeparateScreen || mIsPanorama || mExoPlayer != null) {
            return
        }

        val isContentUri = mMedium.path.startsWith("content://")
        val uri = if (isContentUri) Uri.parse(mMedium.path) else Uri.fromFile(File(mMedium.path))
        val dataSpec = DataSpec(uri)
        val fileDataSource = if (isContentUri) {
            ContentDataSource(requireContext())
        } else {
            FileDataSource()
        }

        try {
            fileDataSource.open(dataSpec)
        } catch (e: Exception) {
            fileDataSource.close()
            activity?.showErrorToast(e)
            return
        }

        val factory = DataSource.Factory { fileDataSource }
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(fileDataSource.uri!!))

        fileDataSource.close()

        mPlayOnPrepared = true

        mExoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(requireContext()))
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
            .apply {
                if (mConfig.loopVideos && listener?.isSlideShowActive() == false) {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
                setMediaSource(mediaSource)
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false
                )
                prepare()

                // SurfaceView is required for HDR output; TextureView keeps pinch zooming working for SDR
                if (mIsHdrVideo) {
                    setVideoSurfaceView(mHdrSurfaceView)
                } else {
                    setVideoTextureView(mTextureView)
                }

                initListeners()
            }
    }

    private fun ExoPlayer.initListeners() {
        addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int
            ) {
                // Reset progress views when video loops.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    mSeekBar.progress = 0
                    mCurrTimeView.text = 0.getFormattedDuration()
                }
            }

            override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> videoPrepared()
                    Player.STATE_ENDED -> videoCompleted()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                mVideoSize.x = videoSize.width
                mVideoSize.y = (videoSize.height / videoSize.pixelWidthHeightRatio).toInt()
                setVideoSize()
            }
        })
    }

    private fun launchVideoPlayer() {
        listener?.launchViewVideoIntent(mMedium.path)
    }

    private fun toggleFullscreen() {
        listener?.fragmentClicked()
    }

    private fun handleDoubleTap(x: Float) {
        val viewWidth = mView.width
        val instantWidth = viewWidth / 7
        when {
            x <= instantWidth -> doSkip(false)
            x >= viewWidth - instantWidth -> doSkip(true)
            else -> togglePlayPause()
        }
    }

    private fun checkExtendedDetails() {
        if (mConfig.showExtendedDetails) {
            binding.videoDetails.apply {
                beInvisible()   // make it invisible so we can measure it, but not show yet
                text = getMediumExtendedDetails(mMedium)
                onGlobalLayout {
                    if (isAdded) {
                        val realY = getExtendedDetailsY(height)
                        if (realY > 0) {
                            y = realY
                            beVisibleIf(text.isNotEmpty())
                            alpha = if (!mConfig.hideExtendedDetails || !mIsFullscreen) 1f else 0f
                        }
                    }
                }
            }
        } else {
            binding.videoDetails.beGone()
        }
    }

    private fun initTimeHolder() {
        var right = 0
        var bottom = requireContext().navigationBarHeight
        if (mConfig.bottomActions) {
            bottom += resources.getDimension(R.dimen.bottom_actions_height).toInt()
        }

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && activity?.hasNavBar() == true) {
            right += requireActivity().navigationBarWidth
        }

        (mTimeHolder.layoutParams as RelativeLayout.LayoutParams).apply {
            bottomMargin = bottom
            rightMargin = right
        }
        mTimeHolder.beInvisibleIf(mIsFullscreen)
    }

    private fun checkIfPanorama() {
        try {
            val fis = FileInputStream(File(mMedium.path))
            fis.use {
                requireContext().parseFileChannel(mMedium.path, it.channel, 0, 0, 0) {
                    mIsPanorama = true
                }
            }
        } catch (ignored: Exception) {
        } catch (ignored: OutOfMemoryError) {
        }
    }

    private fun checkIfHdrVideo() {
        val appContext = context?.applicationContext ?: return
        ensureBackgroundThread {
            val isHdr = HdrHelper.isHdrVideo(appContext, mMedium.path)
            activity?.runOnUiThread {
                if (isAdded) {
                    val wasHdr = mIsHdrVideo
                    mIsHdrVideo = isHdr
                    binding.videoHdrBadge.beVisibleIf(isHdr)
                    updateHdrBadgePosition()
                    binding.videoHdrBadge.alpha = if (mIsFullscreen) 0f else 1f
                    updateVideoSurfaceVisibility()
                    if (wasHdr != isHdr && !binding.videoPreview.isVisible()) {
                        updateVideoSurface()
                    }
                    if (mIsFragmentVisible && requiresHdrColorMode()) {
                        setWindowHdrColorMode(true)
                    } else if (wasHdr) {
                        setWindowHdrColorMode(false)
                    }
                }
            }
        }
    }

    private fun updateVideoSurface() {
        val player = mExoPlayer ?: return
        if (mIsHdrVideo && !binding.videoPreview.isVisible()) {
            player.setVideoSurfaceView(mHdrSurfaceView)
        } else {
            player.setVideoTextureView(mTextureView)
        }
    }

    private fun updateVideoSurfaceVisibility() {
        val hideVideo = mConfig.openVideosOnSeparateScreen || mIsPanorama
        // an empty SurfaceView is opaque black, so keep the thumbnail visible until playback
        // actually starts and the HDR surface has video frames to show
        val showHdrSurface = !hideVideo && mIsHdrVideo && !binding.videoPreview.isVisible()
        mHdrSurfaceView.beVisibleIf(showHdrSurface)
        // TextureView without a player surface is transparent, so the thumbnail can still show
        mTextureView.beVisibleIf(!hideVideo && !showHdrSurface)
        binding.videoSurfaceFrame.beVisibleIf(!hideVideo)
        // a SurfaceView is a separate layer and does not follow the gesture view's canvas matrix,
        // pinch zooming would scale the view hierarchy but not the video itself
        binding.videoSurfaceFrame.controller.settings.isZoomEnabled = !hideVideo && !mIsHdrVideo
        updateHdrSurfaceOffset()
    }

    // the gesture controller centers the TextureView through its canvas matrix, which a SurfaceView
    // ignores, so offset the HDR surface manually to the same place
    private fun updateHdrSurfaceOffset() {
        if (!::mHdrSurfaceView.isInitialized) {
            return
        }
        val frame = binding.videoSurfaceFrame
        val container = mVideoSurfaceContainer
        if (frame.width == 0 || frame.height == 0 || container.width == 0 || container.height == 0) {
            return
        }
        mHdrSurfaceView.translationX = (frame.width - container.width) / 2f
        mHdrSurfaceView.translationY = (frame.height - container.height) / 2f
    }

    // HDR surface output is only composited in HDR when the window asks for it, some ROMs
    // (e.g. HyperOS) otherwise keep tone mapping the video down to SDR
    private fun setWindowHdrColorMode(hdr: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (!hdr && !mIsHdrColorModeActive) {
            return
        }
        activity?.window?.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        mIsHdrColorModeActive = hdr
    }

    fun isHdrVideo() = mIsHdrVideo

    fun requiresHdrColorMode() = mIsHdrVideo && !mIsPanorama &&
        (!::mConfig.isInitialized || !mConfig.openVideosOnSeparateScreen)

    // the pager toolbar is transparent and overlays the content, push the HDR badge below it
    private fun updateHdrBadgePosition() {
        val topMargin = requireContext().statusBarHeight + requireContext().actionBarHeight +
            resources.getDimension(com.simplemobiletools.commons.R.dimen.medium_margin).toInt()
        (binding.videoHdrBadge.layoutParams as RelativeLayout.LayoutParams).apply {
            this.topMargin = topMargin
        }
        binding.videoHdrBadge.layoutParams = binding.videoHdrBadge.layoutParams
    }

    private fun openPanorama() {
        Intent(context, PanoramaVideoActivity::class.java).apply {
            putExtra(PATH, mMedium.path)
            startActivity(this)
        }
    }

    override fun fullscreenToggled(isFullscreen: Boolean) {
        mIsFullscreen = isFullscreen
        val newAlpha = if (isFullscreen) 0f else 1f
        binding.videoHdrBadge.animate().alpha(newAlpha).start()
        if (!mIsFullscreen) {
            mTimeHolder.beVisible()
        }

        mSeekBar.setOnSeekBarChangeListener(if (mIsFullscreen) null else this)
        arrayOf(
            binding.bottomVideoTimeHolder.videoCurrTime,
            binding.bottomVideoTimeHolder.videoDuration,
            binding.bottomVideoTimeHolder.videoTogglePlayPause
        ).forEach {
            it.isClickable = !mIsFullscreen
        }

        mTimeHolder.animate().alpha(newAlpha).start()
        binding.videoDetails.apply {
            if (mStoredShowExtendedDetails && isVisible() && context != null && resources != null) {
                animate().y(getExtendedDetailsY(height))

                if (mStoredHideExtendedDetails) {
                    animate().alpha(newAlpha).start()
                }
            }
        }
    }

    private fun getExtendedDetailsY(height: Int): Float {
        val smallMargin = context?.resources?.getDimension(com.simplemobiletools.commons.R.dimen.small_margin) ?: return 0f
        val fullscreenOffset = smallMargin + if (mIsFullscreen) 0 else requireContext().navigationBarHeight
        var actionsHeight = 0f
        if (!mIsFullscreen) {
            actionsHeight += resources.getDimension(R.dimen.video_player_play_pause_size)
            if (mConfig.bottomActions) {
                actionsHeight += resources.getDimension(R.dimen.bottom_actions_height)
            }
        }
        return requireContext().realScreenSize.y - height - actionsHeight - fullscreenOffset
    }

    private fun skip(forward: Boolean) {
        if (mIsPanorama) {
            return
        } else if (mExoPlayer == null) {
            playVideo()
            return
        }

        mPositionAtPause = 0L
        doSkip(forward)
    }

    private fun doSkip(forward: Boolean) {
        if (mExoPlayer == null) {
            return
        }

        val curr = mExoPlayer!!.currentPosition
        val newProgress = if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        val roundProgress = Math.round(newProgress / 1000f)
        val limitedProgress = Math.max(Math.min(mExoPlayer!!.duration.toInt() / 1000, roundProgress), 0)
        setPosition(limitedProgress)
        if (!mIsPlaying) {
            togglePlayPause()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            if (mExoPlayer != null) {
                if (!mWasPlayerInited) {
                    mPositionWhenInit = progress
                }
                setPosition(progress)
            }

            if (mExoPlayer == null) {
                mPositionAtPause = progress * 1000L
                playVideo()
            }
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (mExoPlayer == null) {
            return
        }

        mExoPlayer!!.playWhenReady = false
        mIsDragged = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (mIsPanorama) {
            openPanorama()
            return
        }

        if (mExoPlayer == null) {
            return
        }

        if (mIsPlaying) {
            mExoPlayer!!.playWhenReady = true
        } else {
            playVideo()
        }

        mIsDragged = false
    }

    private fun togglePlayPause() {
        if (activity == null || !isAdded) {
            return
        }

        if (mIsPlaying) {
            pauseVideo()
        } else {
            playVideo()
        }
    }

    fun playVideo() {
        // reveal the HDR surface only now, an empty SurfaceView would cover the thumbnail with black
        if (mIsHdrVideo && binding.videoPreview.isVisible()) {
            binding.videoPreview.beGone()
            updateVideoSurfaceVisibility()
            updateVideoSurface()
        }

        if (mExoPlayer == null) {
            if (mIsHdrVideo) {
                updateVideoSurfaceVisibility()
            }
            initExoPlayer()
            return
        }

        if (binding.videoPreview.isVisible()) {
            binding.videoPreview.beGone()
            initExoPlayer()
        }

        val wasEnded = videoEnded()
        if (wasEnded) {
            setPosition(0)
        }

        if (mStoredRememberLastVideoPosition && !mWasLastPositionRestored) {
            mWasLastPositionRestored = true
            restoreLastVideoSavedPosition()
        }

        if (!wasEnded || !mConfig.loopVideos) {
            mPlayPauseButton.setImageResource(com.simplemobiletools.commons.R.drawable.ic_pause_outline_vector)
        }

        if (!mWasVideoStarted) {
            binding.videoPlayOutline.beGone()
            mPlayPauseButton.beVisible()
        }

        mWasVideoStarted = true
        if (mIsPlayerPrepared) {
            mIsPlaying = true
        }
        mExoPlayer?.playWhenReady = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun pauseVideo() {
        if (mExoPlayer == null) {
            return
        }

        mIsPlaying = false
        if (!videoEnded()) {
            mExoPlayer?.playWhenReady = false
        }

        mPlayPauseButton.setImageResource(com.simplemobiletools.commons.R.drawable.ic_play_outline_vector)
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mPositionAtPause = mExoPlayer?.currentPosition ?: 0L
    }

    private fun videoEnded(): Boolean {
        val currentPos = mExoPlayer?.currentPosition ?: 0
        val duration = mExoPlayer?.duration ?: 0
        return currentPos != 0L && currentPos >= duration
    }

    private fun setPosition(seconds: Int) {
        mExoPlayer?.seekTo(seconds * 1000L)
        mSeekBar.progress = seconds
        mCurrTimeView.text = seconds.getFormattedDuration()

        if (!mIsPlaying) {
            mPositionAtPause = mExoPlayer?.currentPosition ?: 0L
        }
    }

    private fun setupVideoDuration() {
        ensureBackgroundThread {
            mDuration = context?.getDuration(mMedium.path) ?: 0

            activity?.runOnUiThread {
                setupTimeHolder()
                setPosition(0)
            }
        }
    }

    private fun videoPrepared() {
        if (mDuration == 0) {
            mDuration = (mExoPlayer!!.duration / 1000).toInt()
            setupTimeHolder()
            setPosition(mCurrTime)

            if (mIsFragmentVisible && (mConfig.autoplayVideos)) {
                playVideo()
            }
        }

        if (mPositionWhenInit != 0 && !mWasPlayerInited) {
            setPosition(mPositionWhenInit)
            mPositionWhenInit = 0
        }

        mIsPlayerPrepared = true
        if (mPlayOnPrepared && !mIsPlaying) {
            if (mPositionAtPause != 0L) {
                mExoPlayer?.seekTo(mPositionAtPause)
                mPositionAtPause = 0L
            }
            playVideo()
        }
        mWasPlayerInited = true
        mPlayOnPrepared = false
    }

    private fun videoCompleted() {
        if (!isAdded || mExoPlayer == null) {
            return
        }

        mCurrTime = (mExoPlayer!!.duration / 1000).toInt()
        if (listener?.videoEnded() == false && mConfig.loopVideos) {
            playVideo()
        } else {
            mSeekBar.progress = mSeekBar.max
            mCurrTimeView.text = mDuration.getFormattedDuration()
            pauseVideo()
        }
    }

    private fun cleanup() {
        pauseVideo()
        releaseExoPlayer()

        if (mWasFragmentInit) {
            mCurrTimeView.text = 0.getFormattedDuration()
            mSeekBar.progress = 0
            mTimerHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun releaseExoPlayer() {
        mIsPlayerPrepared = false
        mExoPlayer?.apply {
            stop()
            release()
        }
        mExoPlayer = null
    }

    private fun setVideoSize() {
        if (activity == null || mConfig.openVideosOnSeparateScreen) {
            return
        }

        val videoProportion = mVideoSize.x.toFloat() / mVideoSize.y.toFloat()
        val display = requireActivity().windowManager.defaultDisplay
        val screenWidth: Int
        val screenHeight: Int

        val realMetrics = DisplayMetrics()
        display.getRealMetrics(realMetrics)
        screenWidth = realMetrics.widthPixels
        screenHeight = realMetrics.heightPixels

        val screenProportion = screenWidth.toFloat() / screenHeight.toFloat()

        mVideoSurfaceContainer.layoutParams.apply {
            if (videoProportion > screenProportion) {
                width = screenWidth
                height = (screenWidth.toFloat() / videoProportion).toInt()
            } else {
                width = (videoProportion * screenHeight.toFloat()).toInt()
                height = screenHeight
            }
            mVideoSurfaceContainer.layoutParams = this
        }
        mVideoSurfaceContainer.post { updateHdrSurfaceOffset() }
    }
}
