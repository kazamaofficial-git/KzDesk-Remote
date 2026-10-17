package com.kazamatech.kzdesk.ui

import android.media.MediaExtractor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import android.graphics.Rect
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Canvas
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Local PC-desktop-only MP4 recorder. It never uses MediaProjection or the phone display. */
internal class RemoteScreenRecorder(
    context: Context,
    private val frameProvider: () -> Bitmap?,
    private val captureProtected: () -> Boolean,
    private val pointerProvider: () -> RecordingPointer? = { null },
    /**
     * Gambar frame langsung ke canvas perekam tanpa alokasi. Kalau null atau
     * mengembalikan false, perekam jatuh ke jalur frameProvider (salinan).
     */
    private val frameDrawer: ((Canvas, Rect) -> Boolean)? = null,
    /** Sumber PCM audio PC. Null berarti merekam video saja. */
    private val audioSource: RecordingAudioSource? = null,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)

    private val cursorPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()
    private val cursorRect = Rect()
    @Volatile private var drawTargetRef: Bitmap? = null
    @Volatile private var audioRecorderRef: RemoteAudioRecorder? = null
    @Volatile private var audioSaved = false
    @Volatile private var audioTempRef: File? = null
    @Volatile private var mergedTempRef: File? = null

    @Volatile private var listener: ((RemoteRecordingState) -> Unit)? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var closed = false

    @Synchronized
    fun start(onState: (RemoteRecordingState) -> Unit): Boolean {
        if (closed || worker?.isAlive == true) return false
        listener = onState
        stopRequested.set(false)
        if (isCaptureProtected()) {
            publish(RemoteRecordingState.Failed("secure"))
            return true
        }
        return try {
            worker = Thread({ recordLoop() }, "KzDesk-PC-Recorder").apply {
                // Di atas normal, bukan di bawah. Thread ini punya tenggat 33ms
                // per frame; kalau diprioritaskan di bawah thread UI dan decoder,
                // HP yang sedang sibuk akan membuatnya kehilangan jadwal frame
                // dan hasil rekaman patah walaupun desktop PC-nya lancar.
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            true
        } catch (_: Throwable) {
            worker = null
            publish(RemoteRecordingState.Failed("failed"))
            false
        }
    }

    fun stop() {
        if (worker?.isAlive == true && stopRequested.compareAndSet(false, true)) {
            publish(RemoteRecordingState.Stopping)
        }
    }

    fun close() {
        closed = true
        stop()
        listener = null
    }

    private fun isCaptureProtected(): Boolean = runCatching { captureProtected() }.getOrDefault(true)

    /**
     * Menggambar kursor desktop remote ke frame sebelum frame dikirim ke
     * encoder. Frame dari frameProvider adalah salinan baru tiap iterasi, jadi
     * menggambar di atasnya tidak meninggalkan jejak kursor dari frame
     * sebelumnya.
     */
    private fun drawPointer(frame: Bitmap, existingCanvas: Canvas? = null) {
        val pointer = pointerProvider() ?: return
        if (frame.isRecycled || !frame.isMutable) return
        val scaleX = frame.width.toFloat() / pointer.remoteWidth
        val scaleY = frame.height.toFloat() / pointer.remoteHeight
        val left = pointer.x * scaleX
        val top = pointer.y * scaleY
        // Canvas pakai ulang kalau ada; membuat Canvas baru tiap frame itu
        // alokasi yang tidak perlu di jalur 30 fps.
        val canvas = existingCanvas ?: runCatching { Canvas(frame) }.getOrNull() ?: return
        val remoteCursor = pointer.cursor?.takeIf { !it.isRecycled }
        if (remoteCursor != null) {
            // Bentuk kursor asli dari server, diskalakan mengikuti frame.
            val destLeft = left - pointer.hotX * scaleX
            val destTop = top - pointer.hotY * scaleY
            cursorRect.set(
                destLeft.toInt(),
                destTop.toInt(),
                (destLeft + remoteCursor.width * scaleX).toInt(),
                (destTop + remoteCursor.height * scaleY).toInt(),
            )
            runCatching { canvas.drawBitmap(remoteCursor, null, cursorRect, cursorPaint) }
            return
        }
        // Server hanya memberi tahu "kursor default" tanpa bitmap, jadi digambar
        // sebagai panah sederhana supaya posisinya tetap terlihat di rekaman.
        val size = 18f * minOf(scaleX, scaleY).coerceAtLeast(0.25f)
        arrowPath.reset()
        arrowPath.moveTo(left, top)
        arrowPath.lineTo(left, top + size)
        arrowPath.lineTo(left + size * 0.42f, top + size * 0.66f)
        arrowPath.lineTo(left + size * 0.66f, top + size)
        arrowPath.lineTo(left + size * 0.84f, top + size * 0.9f)
        arrowPath.lineTo(left + size * 0.58f, top + size * 0.56f)
        arrowPath.lineTo(left + size * 0.9f, top + size * 0.48f)
        arrowPath.close()
        arrowFill.color = Color.WHITE
        arrowStroke.color = Color.BLACK
        arrowStroke.strokeWidth = size * 0.12f
        runCatching {
            canvas.drawPath(arrowPath, arrowFill)
            canvas.drawPath(arrowPath, arrowStroke)
        }
    }

    private fun publish(state: RemoteRecordingState) {
        val current = listener ?: return
        mainHandler.post {
            if (listener === current) current(state)
        }
    }

    private fun recordLoop() {
        var codec: MediaCodec? = null
        var codecStarted = false
        var inputSurface: Surface? = null
        var renderer: EglInputRenderer? = null
        var muxer: MediaMuxer? = null
        var drain: CodecOutputDrain? = null
        var muxerStopped = false
        var currentFrame: Bitmap? = null
        var temporaryFile: File? = null

        try {
            if (isCaptureProtected()) throw SecureCaptureException()
            publish(RemoteRecordingState.Preparing)

            val initialFrame = frameProvider() ?: throw NoRemoteFrameException()
            currentFrame = initialFrame
            val (width, height) = encodeDimensions(initialFrame.width, initialFrame.height)
            val outputFile = File(appContext.cacheDir, "kzdesk-pc-${System.currentTimeMillis()}.mp4")
            temporaryFile = outputFile

            // 60 fps bila encoder perangkat mendukung pada ukuran ini, kalau
            // tidak 30 fps. Dua lapis pengaman, karena kueri kemampuan saja
            // tidak cukup: createEncoderByType() bisa memilih codec yang
            // berbeda dari yang dikueri, jadi configure() yang menolak juga
            // harus menurunkan frame rate, bukan menggagalkan rekaman.
            var frameRate = resolveFrameRate(width, height)
            var activeCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            val configured = runCatching {
                activeCodec.configure(
                    videoFormat(width, height, frameRate),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            }
            if (configured.isFailure && frameRate != FALLBACK_FRAME_RATE) {
                runCatching { activeCodec.release() }
                frameRate = FALLBACK_FRAME_RATE
                activeCodec = MediaCodec.createEncoderByType(MIME_TYPE)
                activeCodec.configure(
                    videoFormat(width, height, frameRate),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            }
            val frameDurationNs = 1_000_000_000L / frameRate
            codec = activeCodec
            val encoderSurface = activeCodec.createInputSurface()
            inputSurface = encoderSurface
            val activeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            activeCodec.start()
            codecStarted = true
            // Buffer yang dipakai ulang setiap frame. Tanpa ini, tiap frame
            // membuat bitmap penuh baru lalu membuangnya.
            drawTargetRef = if (frameDrawer != null) {
                runCatching {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            } else null
            val drawTarget: Bitmap? = drawTargetRef
            val drawCanvas: Canvas? = drawTarget?.let { Canvas(it) }
            val frameRect = Rect(0, 0, width, height)
            val activeRenderer = EglInputRenderer(encoderSurface, width, height)
            renderer = activeRenderer
            inputSurface = null // renderer now owns and releases this surface
            val activeDrain = CodecOutputDrain(activeCodec, activeMuxer)
            drain = activeDrain

            // Audio dimulai sebelum loop video supaya keduanya mulai bersamaan.
            // Kegagalan audio tidak boleh menggagalkan video.
            audioSaved = false
            audioRecorderRef = audioSource?.let { source ->
                runCatching {
                    val audioFile = File(
                        outputFile.parentFile,
                        outputFile.nameWithoutExtension + "-audio.m4a",
                    )
                    audioTempRef = audioFile
                    RemoteAudioRecorder(source.sampleRate, source.channelCount, audioFile).also { recorder ->
                        recorder.start()
                        source.attachPcmTap { data, offset, length -> recorder.onPcm(data, offset, length) }
                    }
                }.getOrNull()
            }

            publish(RemoteRecordingState.Recording)
            val startNs = System.nanoTime()
            var frameIndex = 0L
            while (true) {
                if (frameIndex > 0) {
                    val dueNs = startNs + frameIndex * frameDurationNs
                    sleepUntil(dueNs)
                }
                if (isCaptureProtected()) throw SecureCaptureException()

                // Jalur cepat: gambar frame langsung ke buffer pakai ulang, jadi
                // tidak ada bitmap baru per frame. Ukurannya sudah sama dengan
                // encoder, sehingga renderer tidak perlu menskalakan ulang.
                val reused = drawCanvas != null && drawTarget != null &&
                    frameDrawer?.invoke(drawCanvas, frameRect) == true
                val frame: Bitmap
                if (reused) {
                    frame = drawTarget
                } else {
                    val freshFrame = frameProvider()
                    if (freshFrame != null && freshFrame !== currentFrame) {
                        currentFrame?.takeUnless { it.isRecycled }?.recycle()
                        currentFrame = freshFrame
                    }
                    frame = currentFrame ?: throw NoRemoteFrameException()
                }
                drawPointer(frame, drawCanvas)
                activeRenderer.draw(frame, System.nanoTime() - startNs)
                activeDrain.drainAvailable()
                frameIndex += 1
                if (stopRequested.get()) break
            }

            publish(RemoteRecordingState.Stopping)
            activeCodec.signalEndOfInputStream()
            activeRenderer.release()
            renderer = null
            activeDrain.drainUntilEnd()
            activeCodec.stop()
            codecStarted = false
            if (!activeDrain.muxerStarted) throw IllegalStateException("Encoder did not produce an MP4 track")
            activeMuxer.stop()
            muxerStopped = true

            if (isCaptureProtected()) throw SecureCaptureException()
            val location = try {
                val audioFile = audioTempRef
                val hasAudio = audioSaved && audioFile != null &&
                    audioFile.exists() && audioFile.length() > 0
                // Gabungkan lewat remux SETELAH rekaman selesai, bukan dengan
                // menambah track ke muxer video yang sedang jalan. MediaMuxer
                // menuntut semua track ditambahkan sebelum start(), jadi
                // menggabungkan secara live berarti menulis ulang jalur video
                // yang sudah benar. Remux hanya menyalin sampel tanpa encode
                // ulang, dan kalau gagal kita tetap punya dua file yang utuh.
                val mergedFile = if (hasAudio && audioFile != null) {
                    File(outputFile.parentFile, outputFile.nameWithoutExtension + "-merged.mp4")
                } else null
                val combined = if (mergedFile != null && audioFile != null) {
                    mergedTempRef = mergedFile
                    if (muxAudioIntoVideo(outputFile, audioFile, mergedFile)) mergedFile else null
                } else null
                if (combined != null) {
                    val published = publishVideo(combined)
                    runCatching { combined.delete() }
                    mergedTempRef = null
                    runCatching { audioFile?.delete() }
                    audioTempRef = null
                    published
                } else {
                    val video = publishVideo(outputFile)
                    if (hasAudio && audioFile != null) {
                        val audioLocation = runCatching { publishAudio(audioFile) }.getOrNull()
                        if (audioLocation != null) "$video + $audioLocation" else video
                    } else {
                        video
                    }
                }
            } catch (error: Throwable) {
                throw SaveVideoException(error)
            }
            publish(RemoteRecordingState.Saved(location))
        } catch (error: Throwable) {
            val reason = when (error) {
                is SecureCaptureException -> "secure"
                is NoRemoteFrameException -> "no_frame"
                is SaveVideoException -> "save"
                else -> "failed"
            }
            publish(RemoteRecordingState.Failed(reason))
        } finally {
            // Sadapan dilepas lebih dulu agar tidak ada PCM masuk setelah
            // perekam audio berhenti.
            val cleanupAudio = audioRecorderRef
            audioRecorderRef = null
            if (cleanupAudio != null) {
                runCatching { audioSource?.attachPcmTap(null) }
                audioSaved = runCatching { cleanupAudio.stopAndSave() }.getOrDefault(false)
            }
            currentFrame?.takeUnless { it.isRecycled }?.recycle()
            runCatching { renderer?.release() }
            runCatching {
                val target = drawTargetRef
                if (target != null && !target.isRecycled) target.recycle()
                drawTargetRef = null
            }
            runCatching { inputSurface?.release() }
            val cleanupCodec = codec
            if (cleanupCodec != null) {
                if (codecStarted) runCatching { cleanupCodec.stop() }
                runCatching { cleanupCodec.release() }
            }
            val cleanupMuxer = muxer
            if (cleanupMuxer != null) {
                if (!muxerStopped && drain?.muxerStarted == true) runCatching { cleanupMuxer.stop() }
                runCatching { cleanupMuxer.release() }
            }
            temporaryFile?.let { runCatching { it.delete() } }
            audioTempRef?.let { runCatching { it.delete() } }
            audioTempRef = null
            mergedTempRef?.let { runCatching { it.delete() } }
            mergedTempRef = null
            synchronized(this) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun publishVideo(tempFile: File): String {
        val fileName = "KzDesk-PC-${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KzDesk")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw SaveVideoException()
            var completed = false
            try {
                val output = resolver.openOutputStream(uri, "w") ?: throw SaveVideoException()
                output.use { stream -> FileInputStream(tempFile).use { input -> input.copyTo(stream) } }
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, ready, null, null) > 0) { "Unable to publish the MP4 in MediaStore" }
                completed = true
                return "Movies/KzDesk/$fileName"
            } finally {
                if (!completed) runCatching { resolver.delete(uri, null, null) }
            }
        }

        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir
        val directory = File(base, "KzDesk")
        if (!directory.exists() && !directory.mkdirs()) throw SaveVideoException()
        val destination = File(directory, fileName)
        try {
            tempFile.copyTo(destination, overwrite = true)
        } catch (error: Exception) {
            destination.delete()
            throw SaveVideoException(error)
        }
        return "Movies/KzDesk (folder aplikasi)/$fileName"
    }

    /**
     * Gabungkan video dan audio ke satu MP4 dengan menyalin sampel apa adanya
     * (tanpa encode ulang), diurutkan berdasarkan waktu penyajian supaya kedua
     * track terselang-seling dengan benar di dalam file.
     *
     * Return false kalau salah satu sumber tidak punya track yang diharapkan;
     * pemanggil lalu memakai dua file terpisah.
     */
    private fun muxAudioIntoVideo(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = selectTrackIndex(videoExtractor, "video/")
            val audioTrack = selectTrackIndex(audioExtractor, "audio/")
            if (videoTrack < 0 || audioTrack < 0) return false
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val active = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = active
            val outVideo = active.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = active.addTrack(audioExtractor.getTrackFormat(audioTrack))
            active.start()
            started = true

            val videoBuffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            val audioBuffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            var videoReady = readSample(videoExtractor, videoBuffer, videoInfo)
            var audioReady = readSample(audioExtractor, audioBuffer, audioInfo)
            while (videoReady || audioReady) {
                val takeVideo = when {
                    videoReady && audioReady -> videoInfo.presentationTimeUs <= audioInfo.presentationTimeUs
                    videoReady -> true
                    else -> false
                }
                if (takeVideo) {
                    videoBuffer.position(0)
                    videoBuffer.limit(videoInfo.size)
                    active.writeSampleData(outVideo, videoBuffer, videoInfo)
                    videoReady = videoExtractor.advance() && readSample(videoExtractor, videoBuffer, videoInfo)
                } else {
                    audioBuffer.position(0)
                    audioBuffer.limit(audioInfo.size)
                    active.writeSampleData(outAudio, audioBuffer, audioInfo)
                    audioReady = audioExtractor.advance() && readSample(audioExtractor, audioBuffer, audioInfo)
                }
            }
            true
        } catch (error: Throwable) {
            false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private fun readSample(
        extractor: MediaExtractor,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Boolean {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return false
        // MediaExtractor tidak punya getSampleInfo(BufferInfo); metadatanya
        // diambil per properti. SAMPLE_FLAG_SYNC dan BUFFER_FLAG_KEY_FRAME
        // kebetulan bernilai sama, tapi dipetakan eksplisit supaya tidak
        // bergantung pada kebetulan itu.
        info.offset = 0
        info.size = size
        info.presentationTimeUs = extractor.sampleTime
        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
        return true
    }

    /**
     * Simpan rekaman audio ke MediaStore. Cermin dari publishVideo, hanya
     * koleksinya yang berbeda (Music, bukan Movies).
     */
    private fun publishAudio(tempFile: File): String {
        val fileName = "KzDesk-PC-${System.currentTimeMillis()}-audio.m4a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/KzDesk")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "audio gagal disimpan"
            var completed = false
            try {
                val output = resolver.openOutputStream(uri, "w") ?: return "audio gagal disimpan"
                output.use { stream -> FileInputStream(tempFile).use { input -> input.copyTo(stream) } }
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
                completed = true
                return "Music/KzDesk/$fileName"
            } finally {
                if (!completed) runCatching { resolver.delete(uri, null, null) }
            }
        }
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir
        val directory = File(base, "KzDesk")
        if (!directory.exists() && !directory.mkdirs()) return "audio gagal disimpan"
        val destination = File(directory, fileName)
        return try {
            tempFile.copyTo(destination, overwrite = true)
            "Music/KzDesk (folder aplikasi)/$fileName"
        } catch (error: Exception) {
            destination.delete()
            "audio gagal disimpan"
        }
    }

    private fun sleepUntil(targetNs: Long) {
        var remaining = targetNs - System.nanoTime()
        while (remaining > 0L && !stopRequested.get()) {
            val millis = remaining / 1_000_000L
            val nanos = (remaining % 1_000_000L).toInt()
            try {
                Thread.sleep(millis, nanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining = targetNs - System.nanoTime()
        }
    }

    private fun encodeDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val maxEdge = maxOf(sourceWidth, sourceHeight).coerceAtLeast(2)
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxEdge)
        val width = ((sourceWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
        val height = ((sourceHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
        return width to height
    }

    private class CodecOutputDrain(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        var muxerStarted = false
            private set

        fun drainAvailable() {
            while (drainOne(0L) != null) Unit
        }

        fun drainUntilEnd() {
            repeat(MAX_DRAIN_ATTEMPTS) {
                if (drainOne(DRAIN_TIMEOUT_US) == true) return
            }
            throw IllegalStateException("Timed out waiting for encoder EOS")
        }

        /** null = no output yet; true = EOS; false = an output event was consumed. */
        private fun drainOne(timeoutUs: Long): Boolean? {
            val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return null
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted) { "Encoder format changed after MP4 muxer start" }
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
                return false
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return false
            if (index < 0) return false

            val endOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            try {
                val codecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (bufferInfo.size > 0 && !codecConfig) {
                    check(muxerStarted && trackIndex >= 0) { "MP4 muxer is not ready" }
                    val buffer = codec.getOutputBuffer(index) ?: error("Encoder returned an empty output buffer")
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
            return endOfStream
        }
    }

    private class EglInputRenderer(
        private val inputSurface: Surface,
        private val width: Int,
        private val height: Int,
    ) {
        private var display: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var texture = 0
        private var textureInitialized = false
        private var released = false
        private var positionLocation = -1
        private var textureLocation = -1
        private var samplerLocation = -1
        private val positions = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        // Bitmap row zero is its top edge; flip V so the video has normal orientation.
        private val textureCoordinates = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

        init {
            try {
                initialize()
            } catch (error: Throwable) {
                releaseEglObjects()
                throw error
            }
        }

        private fun initialize() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglError("Unable to get EGL display") }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { eglError("Unable to initialize EGL") }

            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, configs.size, count, 0) && count[0] > 0) {
                eglError("No recordable EGL configuration")
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { eglError("Unable to create EGL context") }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { eglError("Unable to create encoder EGL surface") }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { eglError("Unable to bind EGL context") }
            EGL14.eglSwapInterval(display, 0)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            samplerLocation = GLES20.glGetUniformLocation(program, "sTexture")
            check(positionLocation >= 0 && textureLocation >= 0 && samplerLocation >= 0) { "Invalid encoder shader locations" }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            check(texture != 0) { "Unable to create encoder texture" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun draw(source: Bitmap, presentationTimeNs: Long) {
            check(!released) { "Encoder renderer is closed" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { eglError("Unable to bind encoder surface") }
            val scaled = if (source.width == width && source.height == height) null
            else Bitmap.createScaledBitmap(source, width, height, true)
            val frame = scaled ?: source
            try {
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                if (textureInitialized) {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame)
                } else {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
                    textureInitialized = true
                }

                positions.position(0)
                GLES20.glEnableVertexAttribArray(positionLocation)
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions)
                textureCoordinates.position(0)
                GLES20.glEnableVertexAttribArray(textureLocation)
                GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
                GLES20.glUniform1i(samplerLocation, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val glError = GLES20.glGetError()
                check(glError == GLES20.GL_NO_ERROR) { "Encoder OpenGL error 0x${glError.toString(16)}" }
                check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)) {
                    eglError("Unable to timestamp encoded frame")
                }
                check(EGL14.eglSwapBuffers(display, eglSurface)) { eglError("Unable to submit encoded frame") }
            } finally {
                scaled?.takeUnless { it.isRecycled }?.recycle()
            }
        }

        fun release() {
            if (released) return
            released = true
            try {
                releaseEglObjects()
            } finally {
                inputSurface.release()
            }
        }

        private fun releaseEglObjects() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
                    if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            texture = 0
            program = 0
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES20.glCreateProgram()
            check(result != 0) { "Unable to create encoder shader program" }
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(status[0] == GLES20.GL_TRUE) { "Encoder shader link failed: ${GLES20.glGetProgramInfoLog(result)}" }
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Unable to create encoder shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val detail = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Encoder shader compile failed: $detail")
            }
            return shader
        }

        private fun eglError(message: String): String = "$message (EGL 0x${EGL14.eglGetError().toString(16)})"

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D sTexture;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }

    private class SecureCaptureException : Exception()
    private class NoRemoteFrameException : Exception()
    private class SaveVideoException(cause: Throwable? = null) : Exception(cause)

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val MAX_EDGE = 1280
        // 60 fps adalah target, bukan janji: dipakai hanya bila ada encoder AVC
        // yang menyatakan mampu pada ukuran rekaman, dan configure() setuju.
        private const val TARGET_FRAME_RATE = 60
        private const val FALLBACK_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val BITS_PER_PIXEL = 8
        private const val MIN_BIT_RATE = 2_000_000
        private const val MAX_BIT_RATE = 16_000_000

        /**
         * Format video untuk satu frame rate tertentu.
         *
         * Bitrate diskalakan terhadap 30 fps: pada 60 fps dibutuhkan sekitar dua
         * kali bit untuk kualitas yang sama. Atap [MAX_BIT_RATE] tetap berlaku
         * supaya perangkat dengan encoder lemah tidak kewalahan. VBR dipakai
         * karena cocok untuk desktop — area statis (teks, jendela diam) hampir
         * tidak memakai bit, jadi sisanya dipakai area yang benar-benar bergerak.
         */
        private fun videoFormat(width: Int, height: Int, frameRate: Int): MediaFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    (width.toLong() * height * BITS_PER_PIXEL * frameRate / FALLBACK_FRAME_RATE)
                        .toInt()
                        .coerceIn(MIN_BIT_RATE, MAX_BIT_RATE),
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }

        /**
         * [TARGET_FRAME_RATE] bila ada encoder AVC yang menyatakan mampu pada
         * ukuran ini, selain itu [FALLBACK_FRAME_RATE].
         *
         * Seluruh kueri dibungkus runCatching: beberapa ROM melempar exception
         * saat menanyakan kemampuan codec, dan itu tidak boleh menggagalkan
         * rekaman — cukup turun ke 30 fps.
         */
        private fun resolveFrameRate(width: Int, height: Int): Int = runCatching {
            val supported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .mapNotNull { info ->
                    runCatching { info.getCapabilitiesForType(MIME_TYPE) }.getOrNull()
                        ?.videoCapabilities
                }
                .any { caps ->
                    caps.isSizeSupported(width, height) &&
                        caps.areSizeAndRateSupported(width, height, TARGET_FRAME_RATE.toDouble())
                }
            if (supported) TARGET_FRAME_RATE else FALLBACK_FRAME_RATE
        }.getOrDefault(FALLBACK_FRAME_RATE)
        private const val SAMPLE_BUFFER_BYTES = 1 shl 20
        private const val DRAIN_TIMEOUT_US = 100_000L
        private const val MAX_DRAIN_ATTEMPTS = 300
    }
}


internal sealed interface RemoteRecordingState {
    data object Idle : RemoteRecordingState
    data object Preparing : RemoteRecordingState
    data object Recording : RemoteRecordingState
    data object Stopping : RemoteRecordingState
    data class Saved(val location: String) : RemoteRecordingState
    data class Failed(val reason: String) : RemoteRecordingState
}

/** Posisi kursor desktop remote yang perlu digambar ke frame rekaman. */
internal class RecordingPointer(
    val x: Float,
    val y: Float,
    val remoteWidth: Int,
    val remoteHeight: Int,
    val cursor: Bitmap?,
    val hotX: Int,
    val hotY: Int,
    val visible: Boolean,
)

/**
 * Kotak berbagi antara komposisi (thread UI) dan thread perekaman.
 *
 * Field-nya volatile karena ditulis saat recomposition dan dibaca worker
 * perekaman setiap frame. Dipakai sebagai kotak, bukan StateFlow, supaya thread
 * worker tidak perlu bergantung pada runtime Compose.
 */
internal class RecordingPointerBox {
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var remoteWidth = 0
    @Volatile var remoteHeight = 0
    @Volatile var cursor: Bitmap? = null
    @Volatile var hotX = 0
    @Volatile var hotY = 0
    @Volatile var visible = false

    fun current(): RecordingPointer? {
        if (!visible) return null
        val width = remoteWidth
        val height = remoteHeight
        if (width <= 0 || height <= 0) return null
        return RecordingPointer(x, y, width, height, cursor, hotX, hotY, true)
    }
}

/** Sumber PCM audio PC yang bisa disadap perekaman. */
internal interface RecordingAudioSource {
    val sampleRate: Int
    val channelCount: Int
    fun attachPcmTap(tap: ((ByteArray, Int, Int) -> Unit)?)
}

/**
 * Perekam audio PC (PCM16 -> AAC di .m4a).
 *
 * Sengaja jadi file terpisah, bukan track kedua di dalam MP4 video. MediaMuxer
 * menuntut semua track ditambahkan sebelum start(), jadi menggabungkan berarti
 * menulis ulang jalur muxer video yang sudah berjalan dan berisiko merusak
 * rekaman yang sekarang sudah benar. Dipisahkan, jalur video tidak tersentuh
 * sama sekali: kalau audio gagal, rekaman video tetap utuh.
 *
 * PCM disalin per paket karena buffer milik bridge dipakai ulang untuk paket
 * berikutnya. Antrean bounded: kalau encoder tertinggal, paket tertua dibuang
 * -- audio sedikit terpotong lebih baik daripada memori membengkak.
 */
internal class RemoteAudioRecorder(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val outputFile: File,
) {
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_PACKETS)
    private val stopRequested = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var thread: Thread? = null
    @Volatile private var trackIndex = -1
    @Volatile private var muxerStarted = false
    @Volatile private var saved = false

    fun onPcm(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || stopRequested.get()) return
        val copy = runCatching { data.copyOfRange(offset, offset + length) }.getOrNull() ?: return
        while (!queue.offer(copy)) {
            if (queue.poll() == null) return
        }
    }

    fun start() {
        val worker = Thread({ runLoop() }, "KzDesk-PC-AudioRec")
        thread = worker
        worker.start()
    }

    /** Hentikan dan rapikan. Return true kalau file audio benar-benar tertulis. */
    fun stopAndSave(): Boolean {
        stopRequested.set(true)
        runCatching { thread?.join(JOIN_TIMEOUT_MS) }
        thread = null
        return saved
    }

    private fun runLoop() {
        val codec = runCatching { MediaCodec.createEncoderByType(AUDIO_MIME) }.getOrNull() ?: return
        var muxer: MediaMuxer? = null
        var totalBytes = 0L
        val bytesPerSecond = (sampleRate.toLong() * channelCount * 2L).coerceAtLeast(1L)
        try {
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            while (true) {
                // Kuras keluaran lebih dulu supaya buffer input tidak habis.
                if (drainOutput(codec, muxer, 0L)) break
                val pcm = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    if (stopRequested.get()) break
                    continue
                }
                val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex < 0) continue
                val buffer = codec.getInputBuffer(inIndex)
                if (buffer == null) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                    continue
                }
                buffer.clear()
                buffer.put(pcm)
                val presentationUs = totalBytes * 1_000_000L / bytesPerSecond
                totalBytes += pcm.size
                codec.queueInputBuffer(inIndex, 0, pcm.size, presentationUs, 0)
            }
            // EOS untuk encoder audio dikirim lewat buffer input kosong berflag
            // EOS; signalEndOfInputStream() hanya untuk encoder berbasis surface.
            val eosIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (eosIndex >= 0) {
                codec.queueInputBuffer(
                    eosIndex, 0, 0,
                    totalBytes * 1_000_000L / bytesPerSecond,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            var attempts = 0
            var ended = false
            while (attempts < MAX_DRAIN_ATTEMPTS && !ended) {
                ended = drainOutput(codec, muxer, DRAIN_TIMEOUT_US)
                attempts += 1
            }
            if (muxerStarted) {
                muxer?.stop()
                saved = true
            }
        } catch (_: Throwable) {
            saved = false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.release() }
            if (!saved) runCatching { outputFile.delete() }
        }
    }

    private fun drainOutput(codec: MediaCodec, muxer: MediaMuxer?, timeoutUs: Long): Boolean {
        val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (muxer != null && !muxerStarted) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            }
            return false
        }
        if (index < 0) return false
        val endOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        try {
            val codecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (bufferInfo.size > 0 && !codecConfig && muxerStarted && trackIndex >= 0) {
                val buffer = codec.getOutputBuffer(index) ?: return endOfStream
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                muxer?.writeSampleData(trackIndex, buffer, bufferInfo)
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
        return endOfStream
    }

    private companion object {
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val AUDIO_BIT_RATE = 128_000
        const val MAX_QUEUE_PACKETS = 96
        const val POLL_TIMEOUT_MS = 20L
        const val INPUT_TIMEOUT_US = 10_000L
        const val DRAIN_TIMEOUT_US = 10_000L
        const val MAX_DRAIN_ATTEMPTS = 200
        const val JOIN_TIMEOUT_MS = 3_000L
    }
}
