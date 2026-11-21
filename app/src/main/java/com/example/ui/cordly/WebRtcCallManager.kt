package com.example.ui.cordly

import android.content.Context
import android.util.Log
import com.example.data.model.CallSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency P2P WebRTC audio/video engine with:
 * - Pre-negotiated SEND_RECV audio + video transceivers (instant camera toggle with zero extra SDP delay)
 * - WebRTC Perfect Negotiation (polite/impolite collision resolution & serialized SDP state machine)
 * - MAXBUNDLE + RTCP_MUX + UDP-only fast ICE + low-packet audio jitter buffer (~40-80ms voice)
 * - Adaptive MAINTAIN_FRAMERATE bitrate limits so video stays smooth and never starves voice
 */
class WebRtcCallManager(
    context: Context,
    private val currentUserId: String,
    private val onSendSignal: (
        recipientId: String,
        type: String,
        sdp: String?,
        candidate: String?,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ) -> Unit,
    private val onConsumeSignal: (signalId: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val released = AtomicBoolean(false)

    val eglBase: EglBase = EglBase.create()

    private val audioDeviceModule: JavaAudioDeviceModule?
    private val factory: PeerConnectionFactory

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrackImpl: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val audioTransceivers = ConcurrentHashMap<String, RtpTransceiver>()
    private val videoTransceivers = ConcurrentHashMap<String, RtpTransceiver>()
    private val pendingIceByPeer = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val remoteDescSetPeers = ConcurrentHashMap<String, Boolean>()
    private val processedSignalIds = ConcurrentHashMap.newKeySet<String>()
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()

    // Perfect Negotiation & serialized SDP queue per peer
    private val sdpBusyPeers = ConcurrentHashMap<String, Boolean>()
    private val makingOfferPeers = ConcurrentHashMap<String, Boolean>()
    private val ignoreOfferPeers = ConcurrentHashMap<String, Boolean>()
    private val pendingRenegotiatePeers = ConcurrentHashMap<String, Boolean>()
    private val queuedSdpSignals = ConcurrentHashMap<String, ArrayDeque<CallSignal>>()
    private val initialOfferTriggered = ConcurrentHashMap.newKeySet<String>()
    private val lastIceRestartMs = ConcurrentHashMap<String, Long>()

    private var currentPreset: VideoQualityPreset = VideoQualityPreset.Q480P
    private var cameraStarted = false
    private var audioStarted = false
    private var currentMuted = false
    private var currentVideoEnabled = false
    private var callIsActive = false

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _isUsingFrontCamera = MutableStateFlow(true)
    val isUsingFrontCamera: StateFlow<Boolean> = _isUsingFrontCamera.asStateFlow()

    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
    )

    init {
        ensureFactoryInitialized(appContext)
        audioDeviceModule = try {
            JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
        } catch (t: Throwable) {
            Log.w(TAG, "JavaAudioDeviceModule fallback", t)
            null
        }
        val builder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
        if (audioDeviceModule != null) {
            builder.setAudioDeviceModule(audioDeviceModule)
        }
        factory = builder.createPeerConnectionFactory()
    }

    private fun isPoliteFor(peerId: String): Boolean = currentUserId > peerId

    fun startOrUpdateLocalMedia(
        hasAudioPermission: Boolean,
        hasCameraPermission: Boolean,
        videoEnabled: Boolean,
        muted: Boolean,
        preset: VideoQualityPreset
    ) {
        if (released.get()) return
        executor.execute {
            if (released.get()) return@execute
            currentMuted = muted
            currentVideoEnabled = videoEnabled && hasCameraPermission
            val qualityChanged = currentPreset != preset
            currentPreset = preset

            if (hasAudioPermission && !audioStarted) {
                startLocalAudioInternal()
            }
            localAudioTrack?.setEnabled(!muted)

            if (hasCameraPermission && videoEnabled && !cameraStarted) {
                startLocalCameraInternal(preset)
            }
            localVideoTrackImpl?.setEnabled(currentVideoEnabled)

            if (cameraStarted && qualityChanged) {
                applyVideoQualityInternal(preset)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        if (released.get()) return
        executor.execute {
            if (released.get()) return@execute
            currentMuted = muted
            localAudioTrack?.setEnabled(!muted)
        }
    }

    fun setVideoEnabled(enabled: Boolean, hasCameraPermission: Boolean) {
        if (released.get()) return
        executor.execute {
            if (released.get()) return@execute
            currentVideoEnabled = enabled && hasCameraPermission
            if (currentVideoEnabled && !cameraStarted) {
                startLocalCameraInternal(currentPreset)
            }
            localVideoTrackImpl?.setEnabled(currentVideoEnabled)
            videoTransceivers.values.forEach { transceiver ->
                try {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    }
                    if (currentVideoEnabled && localVideoTrackImpl != null && transceiver.sender.track() == null) {
                        transceiver.sender.setTrack(localVideoTrackImpl, false)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun switchCamera() {
        if (released.get()) return
        executor.execute {
            val capturer = videoCapturer ?: return@execute
            try {
                capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        _isUsingFrontCamera.value = isFrontCamera
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {
                        Log.w(TAG, "Camera switch error: $errorDescription")
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "switchCamera failed", t)
            }
        }
    }

    fun applyVideoQuality(preset: VideoQualityPreset) {
        if (released.get()) return
        executor.execute {
            if (released.get()) return@execute
            currentPreset = preset
            applyVideoQualityInternal(preset)
        }
    }

    fun ensurePeersAndConnect(
        callStatus: String,
        participantIds: List<String>
    ) {
        if (released.get() || callStatus != "active") return
        val remotePeers = participantIds.filter { it.isNotBlank() && it != currentUserId }.distinct()
        executor.execute {
            if (released.get()) return@execute
            callIsActive = true
            remotePeers.forEach { peerId ->
                val pc = getOrCreatePeerConnection(peerId) ?: return@forEach
                // Lower UID initiates the first offer cleanly once; Perfect Negotiation resolves any glare.
                if (currentUserId < peerId && initialOfferTriggered.add(peerId)) {
                    requestNegotiation(peerId, pc)
                }
            }
        }
    }

    fun handleIncomingSignals(signals: List<CallSignal>) {
        if (released.get() || signals.isEmpty()) return
        val ordered = signals.sortedWith(
            compareBy<CallSignal>(
                { it.createdAt?.seconds ?: 0L },
                { it.createdAt?.nanoseconds ?: 0 },
                {
                    when (it.type) {
                        "offer" -> 0
                        "answer" -> 1
                        else -> 2
                    }
                }
            )
        )
        ordered.forEach { signal ->
            val sigId = signal.id
            if (sigId.isNotBlank() && !processedSignalIds.add(sigId)) {
                return@forEach
            }
            executor.execute {
                if (released.get()) return@execute
                enqueueOrProcessSignal(signal)
                if (sigId.isNotBlank()) {
                    onConsumeSignal(sigId)
                }
            }
        }
    }

    private fun startLocalAudioInternal() {
        if (audioStarted) return
        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            }
            val src = factory.createAudioSource(audioConstraints)
            val track = factory.createAudioTrack("NEOV_AUDIO_0", src)
            track.setEnabled(!currentMuted)
            audioSource = src
            localAudioTrack = track
            audioStarted = true

            peerConnections.forEach { (peerId, pc) ->
                attachAudioTrackToPeer(peerId, pc, track)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start local audio track", t)
        }
    }

    private fun startLocalCameraInternal(preset: VideoQualityPreset) {
        if (cameraStarted) return
        try {
            val enumerator = Camera2Enumerator(appContext)
            val capturer = createCapturer(enumerator) ?: return
            val helper = SurfaceTextureHelper.create("NeovCaptureThread", eglBase.eglBaseContext)
            val src = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, src.capturerObserver)
            capturer.startCapture(preset.captureWidth, preset.captureHeight, preset.captureFps)
            val track = factory.createVideoTrack("NEOV_VIDEO_0", src)
            track.setEnabled(currentVideoEnabled)

            videoCapturer = capturer
            surfaceTextureHelper = helper
            videoSource = src
            localVideoTrackImpl = track
            _localVideoTrack.value = track
            cameraStarted = true

            peerConnections.forEach { (peerId, pc) ->
                attachVideoTrackToPeer(peerId, pc, track)
                applyVideoBitrateToPeer(pc, preset)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start local camera", t)
        }
    }

    private fun attachAudioTrackToPeer(peerId: String, pc: PeerConnection, track: AudioTrack) {
        try {
            val transceiver = audioTransceivers[peerId]
                ?: pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                    ?.also { audioTransceivers[peerId] = it }
            if (transceiver != null) {
                if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
                if (transceiver.sender.track() == null) {
                    transceiver.sender.setTrack(track, false)
                }
            } else {
                val created = pc.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        listOf("NEOV_STREAM")
                    )
                )
                audioTransceivers[peerId] = created
            }
            applyAudioBitrateToPeer(pc)
        } catch (t: Throwable) {
            Log.w(TAG, "attachAudioTrackToPeer fallback", t)
        }
    }

    private fun attachVideoTrackToPeer(peerId: String, pc: PeerConnection, track: VideoTrack) {
        try {
            val transceiver = videoTransceivers[peerId]
                ?: pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                    ?.also { videoTransceivers[peerId] = it }
            if (transceiver != null) {
                if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
                if (transceiver.sender.track() == null) {
                    transceiver.sender.setTrack(track, false)
                }
            } else {
                val created = pc.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        listOf("NEOV_STREAM")
                    )
                )
                videoTransceivers[peerId] = created
            }
            applyVideoBitrateToPeer(pc, currentPreset)
        } catch (t: Throwable) {
            Log.w(TAG, "attachVideoTrackToPeer fallback", t)
        }
    }

    private fun createCapturer(enumerator: CameraEnumerator): CameraVideoCapturer? {
        val names = enumerator.deviceNames
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let { name ->
            enumerator.createCapturer(name, null)?.let {
                _isUsingFrontCamera.value = true
                return it
            }
        }
        names.firstOrNull()?.let { name ->
            enumerator.createCapturer(name, null)?.let {
                _isUsingFrontCamera.value = false
                return it
            }
        }
        return null
    }

    private fun applyVideoQualityInternal(preset: VideoQualityPreset) {
        try {
            videoCapturer?.changeCaptureFormat(preset.captureWidth, preset.captureHeight, preset.captureFps)
        } catch (t: Throwable) {
            Log.w(TAG, "changeCaptureFormat fallback for ${preset.id}", t)
        }
        peerConnections.values.forEach { pc ->
            applyVideoBitrateToPeer(pc, preset)
        }
    }

    private fun applyVideoBitrateToPeer(pc: PeerConnection, preset: VideoQualityPreset) {
        try {
            pc.senders.forEach { sender ->
                if (sender.track()?.kind() == "video" ||
                    videoTransceivers.values.any { it.sender == sender }
                ) {
                    val params = sender.parameters
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    params.encodings.forEach { enc ->
                        enc.minBitrateBps = 120_000
                        enc.maxBitrateBps = preset.maxBitrateBps
                        enc.maxFramerate = preset.captureFps
                    }
                    sender.parameters = params
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun applyAudioBitrateToPeer(pc: PeerConnection) {
        try {
            pc.senders.forEach { sender ->
                if (sender.track()?.kind() == "audio") {
                    val params = sender.parameters
                    params.encodings.forEach { enc ->
                        enc.maxBitrateBps = 48_000
                    }
                    sender.parameters = params
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun getOrCreatePeerConnection(peerId: String): PeerConnection? {
        peerConnections[peerId]?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            audioJitterBufferMaxPackets = 25
            audioJitterBufferFastAccelerate = true
            enableDscp = true
            enableImplicitRollback = true
            enableCpuOveruseDetection = true
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                if (state == PeerConnection.SignalingState.STABLE) {
                    executor.execute {
                        if (!released.get()) {
                            drainNegotiationQueue(peerId)
                        }
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    connectedPeers.add(peerId)
                    executor.execute {
                        if (!released.get()) {
                            peerConnections[peerId]?.let {
                                applyAudioBitrateToPeer(it)
                                applyVideoBitrateToPeer(it, currentPreset)
                            }
                        }
                    }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    connectedPeers.remove(peerId)
                    if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.DISCONNECTED
                    ) {
                        scheduleIceRecovery(peerId)
                    }
                }
                _connectedPeerCount.value = connectedPeers.size
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null || released.get()) return
                onSendSignal(
                    peerId,
                    "ice",
                    null,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull() ?: return
                _remoteVideoTracks.value = _remoteVideoTracks.value + (peerId to track)
            }

            override fun onRemoveStream(stream: MediaStream?) {
                _remoteVideoTracks.value = _remoteVideoTracks.value - peerId
            }

            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {
                executor.execute {
                    if (released.get() || !callIsActive) return@execute
                    val activePc = peerConnections[peerId] ?: return@execute
                    requestNegotiation(peerId, activePc)
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (peerId to track)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (peerId to track)
                }
            }
        }) ?: return null

        // Always pre-allocate SEND_RECV audio & video transceivers so toggling camera/mic
        // mid-call streams immediately over the existing ICE transport without waiting for SDP!
        val initSendRecv = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
            listOf("NEOV_STREAM")
        )
        val audioTx = localAudioTrack?.let { pc.addTransceiver(it, initSendRecv) }
            ?: pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, initSendRecv)
        audioTransceivers[peerId] = audioTx

        val videoTx = localVideoTrackImpl?.let { pc.addTransceiver(it, initSendRecv) }
            ?: pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, initSendRecv)
        videoTransceivers[peerId] = videoTx

        applyAudioBitrateToPeer(pc)
        applyVideoBitrateToPeer(pc, currentPreset)
        peerConnections[peerId] = pc
        return pc
    }

    private fun scheduleIceRecovery(peerId: String) {
        if (released.get() || isPoliteFor(peerId)) return
        val now = System.currentTimeMillis()
        val prev = lastIceRestartMs[peerId] ?: 0L
        if (now - prev < 5_000L) return
        lastIceRestartMs[peerId] = now
        try {
            watchdogExecutor.schedule({
                if (released.get() || connectedPeers.contains(peerId)) return@schedule
                executor.execute {
                    if (released.get() || connectedPeers.contains(peerId)) return@execute
                    val pc = peerConnections[peerId] ?: return@execute
                    try {
                        pc.restartIce()
                        requestNegotiation(peerId, pc)
                    } catch (_: Exception) {
                    }
                }
            }, 3500L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun requestNegotiation(peerId: String, pc: PeerConnection) {
        pendingRenegotiatePeers[peerId] = true
        drainNegotiationQueue(peerId, pc)
    }

    private fun enqueueOrProcessSignal(signal: CallSignal) {
        val peerId = signal.senderId
        if (peerId.isBlank() || peerId == currentUserId) return
        val pc = getOrCreatePeerConnection(peerId) ?: return

        if (signal.type == "ice") {
            val candStr = signal.candidate ?: return
            val mid = signal.sdpMid ?: "0"
            val mLine = signal.sdpMLineIndex ?: 0
            val ice = IceCandidate(mid, mLine, candStr)
            if (ignoreOfferPeers[peerId] == true) return
            if (remoteDescSetPeers[peerId] == true) {
                try {
                    pc.addIceCandidate(ice)
                } catch (_: Exception) {
                }
            } else {
                pendingIceByPeer.getOrPut(peerId) { mutableListOf() }.add(ice)
            }
            return
        }

        val queue = queuedSdpSignals.getOrPut(peerId) { ArrayDeque() }
        queue.addLast(signal)
        drainNegotiationQueue(peerId, pc)
    }

    private fun drainNegotiationQueue(peerId: String, pcOverride: PeerConnection? = null) {
        if (released.get()) return
        if (sdpBusyPeers[peerId] == true) return
        val pc = pcOverride ?: peerConnections[peerId] ?: return

        val queue = queuedSdpSignals[peerId]
        val nextSignal = if (queue != null && queue.isNotEmpty()) queue.removeFirst() else null
        if (nextSignal != null) {
            processSdpSignal(peerId, pc, nextSignal)
            return
        }

        if (pendingRenegotiatePeers[peerId] == true &&
            pc.signalingState() == PeerConnection.SignalingState.STABLE
        ) {
            pendingRenegotiatePeers.remove(peerId)
            createAndSendOffer(peerId, pc)
        }
    }

    private fun finishSdpStep(peerId: String) {
        executor.execute {
            if (released.get()) return@execute
            sdpBusyPeers[peerId] = false
            drainNegotiationQueue(peerId)
        }
    }

    private fun createAndSendOffer(peerId: String, pc: PeerConnection) {
        sdpBusyPeers[peerId] = true
        makingOfferPeers[peerId] = true
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null || released.get()) {
                    makingOfferPeers[peerId] = false
                    finishSdpStep(peerId)
                    return
                }
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        makingOfferPeers[peerId] = false
                        val localSdp = pc.localDescription ?: desc
                        onSendSignal(peerId, "offer", localSdp.description, null, null, null)
                        finishSdpStep(peerId)
                    }

                    override fun onSetFailure(error: String?) {
                        super.onSetFailure(error)
                        makingOfferPeers[peerId] = false
                        finishSdpStep(peerId)
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                super.onCreateFailure(error)
                makingOfferPeers[peerId] = false
                finishSdpStep(peerId)
            }
        }, constraints)
    }

    private fun processSdpSignal(peerId: String, pc: PeerConnection, signal: CallSignal) {
        when (signal.type) {
            "offer" -> {
                val sdpText = signal.sdp ?: return
                val state = pc.signalingState()
                val offerCollision = makingOfferPeers[peerId] == true ||
                    state != PeerConnection.SignalingState.STABLE
                val polite = isPoliteFor(peerId)
                val shouldIgnore = !polite && offerCollision
                ignoreOfferPeers[peerId] = shouldIgnore
                if (shouldIgnore) {
                    drainNegotiationQueue(peerId, pc)
                    return
                }

                sdpBusyPeers[peerId] = true
                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpText)
                val applyRemoteAndAnswer = {
                    pc.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            remoteDescSetPeers[peerId] = true
                            flushPendingIce(peerId, pc)
                            val answerConstraints = MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            }
                            pc.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                    if (answerDesc == null || released.get()) {
                                        finishSdpStep(peerId)
                                        return
                                    }
                                    pc.setLocalDescription(object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            val localAns = pc.localDescription ?: answerDesc
                                            onSendSignal(peerId, "answer", localAns.description, null, null, null)
                                            finishSdpStep(peerId)
                                        }

                                        override fun onSetFailure(error: String?) {
                                            super.onSetFailure(error)
                                            finishSdpStep(peerId)
                                        }
                                    }, answerDesc)
                                }

                                override fun onCreateFailure(error: String?) {
                                    super.onCreateFailure(error)
                                    finishSdpStep(peerId)
                                }
                            }, answerConstraints)
                        }

                        override fun onSetFailure(error: String?) {
                            super.onSetFailure(error)
                            finishSdpStep(peerId)
                        }
                    }, remoteSdp)
                }

                if (offerCollision && polite && state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            applyRemoteAndAnswer()
                        }

                        override fun onSetFailure(error: String?) {
                            super.onSetFailure(error)
                            applyRemoteAndAnswer()
                        }
                    }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                } else {
                    applyRemoteAndAnswer()
                }
            }

            "answer" -> {
                val sdpText = signal.sdp ?: return
                if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    drainNegotiationQueue(peerId, pc)
                    return
                }
                sdpBusyPeers[peerId] = true
                val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpText)
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        remoteDescSetPeers[peerId] = true
                        flushPendingIce(peerId, pc)
                        finishSdpStep(peerId)
                    }

                    override fun onSetFailure(error: String?) {
                        super.onSetFailure(error)
                        finishSdpStep(peerId)
                    }
                }, remoteSdp)
            }
        }
    }

    private fun flushPendingIce(peerId: String, pc: PeerConnection) {
        val queued = pendingIceByPeer.remove(peerId) ?: return
        queued.forEach { cand ->
            try {
                pc.addIceCandidate(cand)
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        _localVideoTrack.value = null
        _remoteVideoTracks.value = emptyMap()
        _connectedPeerCount.value = 0

        try {
            watchdogExecutor.shutdownNow()
        } catch (_: Exception) {
        }

        executor.execute {
            try {
                peerConnections.values.forEach { pc ->
                    try {
                        pc.close()
                        pc.dispose()
                    } catch (_: Exception) {
                    }
                }
                peerConnections.clear()
                audioTransceivers.clear()
                videoTransceivers.clear()
                pendingIceByPeer.clear()
                remoteDescSetPeers.clear()
                sdpBusyPeers.clear()
                makingOfferPeers.clear()
                ignoreOfferPeers.clear()
                pendingRenegotiatePeers.clear()
                queuedSdpSignals.clear()
                initialOfferTriggered.clear()
                connectedPeers.clear()

                try {
                    videoCapturer?.stopCapture()
                } catch (_: Exception) {
                }
                try {
                    videoCapturer?.dispose()
                } catch (_: Exception) {
                }
                videoCapturer = null

                try {
                    localVideoTrackImpl?.dispose()
                } catch (_: Exception) {
                }
                localVideoTrackImpl = null

                try {
                    videoSource?.dispose()
                } catch (_: Exception) {
                }
                videoSource = null

                try {
                    surfaceTextureHelper?.dispose()
                } catch (_: Exception) {
                }
                surfaceTextureHelper = null

                try {
                    localAudioTrack?.dispose()
                } catch (_: Exception) {
                }
                localAudioTrack = null

                try {
                    audioSource?.dispose()
                } catch (_: Exception) {
                }
                audioSource = null

                try {
                    audioDeviceModule?.release()
                } catch (_: Exception) {
                }
                try {
                    factory.dispose()
                } catch (_: Exception) {
                }
                try {
                    eglBase.release()
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }
        }
        executor.shutdown()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "SDP create failure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "SDP set failure: $error")
        }
    }

    companion object {
        private const val TAG = "NeovWebRTC"
        private val initialized = AtomicBoolean(false)

        fun ensureFactoryInitialized(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }
}
