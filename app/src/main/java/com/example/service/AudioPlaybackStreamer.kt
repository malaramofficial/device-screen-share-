package com.example.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures playback audio (not microphone audio) from apps that permit Android playback capture.
 * Packets: "TCAU" + sampleRate(int32 LE) + channelCount(int16 LE) + PCM16 samples.
 */
class AudioPlaybackStreamer(
  private val projection: MediaProjection,
  private val onPacket: (ByteArray) -> Unit,
  private val onError: (String) -> Unit,
) {
  private val running = AtomicBoolean(false)
  private var thread: Thread? = null
  private var recorder: AudioRecord? = null

  fun start() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      onError("Audio sharing requires Android 10 or newer.")
      return
    }
    if (!running.compareAndSet(false, true)) return
    thread = Thread({
      try {
        val sampleRate = 44100
        val channels = 2
        val format = AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
          .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
          .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
          .addMatchingUsage(AudioAttributes.USAGE_GAME)
          .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
          .build()
        val minBytes = AudioRecord.getMinBufferSize(
          sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val audioRecord = AudioRecord.Builder()
          .setAudioFormat(format)
          .setBufferSizeInBytes(minBytes * 2)
          .setAudioPlaybackCaptureConfig(config)
          .build()
        recorder = audioRecord
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
          throw IllegalStateException("Android could not initialize playback audio capture.")
        }
        audioRecord.startRecording()
        val pcm = ByteArray(4096)
        while (running.get()) {
          val count = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
          if (count > 0) {
            val packet = ByteBuffer.allocate(10 + count).order(ByteOrder.LITTLE_ENDIAN)
            packet.put(byteArrayOf(0x54, 0x43, 0x41, 0x55)) // TCAU
            packet.putInt(sampleRate)
            packet.putShort(channels.toShort())
            packet.put(pcm, 0, count)
            onPacket(packet.array())
          } else if (count < 0 && running.get()) {
            throw IllegalStateException("AudioRecord read failed: $count")
          }
        }
      } catch (e: Exception) {
        if (running.get()) onError(e.localizedMessage ?: "Playback audio capture failed.")
      } finally {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        running.set(false)
      }
    }, "TermuxCastPlaybackAudio").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running.set(false)
    runCatching { recorder?.stop() }
    runCatching { thread?.join(500) }
    thread = null
  }
}
