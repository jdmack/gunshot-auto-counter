package com.james.shotcounterpoc.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    /**
     * Writes [samples] as a 16-bit mono PCM WAV file at [sampleRate] Hz.
     * The file is created (or overwritten) at [file].
     */
    fun write(file: File, sampleRate: Int, samples: ShortArray) {
        val dataBytes = samples.size * 2
        file.outputStream().buffered().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeLE32(36 + dataBytes)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeLE32(16)          // chunk size
            out.writeLE16(1)           // PCM format
            out.writeLE16(1)           // mono
            out.writeLE32(sampleRate)
            out.writeLE32(sampleRate * 2) // byte rate (sampleRate * channels * bitsPerSample/8)
            out.writeLE16(2)           // block align
            out.writeLE16(16)          // bits per sample
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeLE32(dataBytes)
            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort(s)
            out.write(buf.array())
        }
    }

    private fun OutputStream.writeLE32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun OutputStream.writeLE16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
