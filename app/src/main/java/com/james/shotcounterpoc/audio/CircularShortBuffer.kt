package com.james.shotcounterpoc.audio

/**
 * Thread-unsafe circular buffer for PCM audio samples. Intended for use
 * within a single recording coroutine to maintain a rolling pre-trigger window.
 */
class CircularShortBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var writePos = 0
    private var count = 0

    fun write(src: ShortArray, length: Int) {
        for (i in 0 until length) {
            data[writePos] = src[i]
            writePos = (writePos + 1) % capacity
            if (count < capacity) count++
        }
    }

    /** Returns a linear copy of the buffer contents in chronological order. */
    fun snapshot(): ShortArray {
        val size = count
        val result = ShortArray(size)
        val startPos = if (count < capacity) 0 else writePos
        for (i in 0 until size) {
            result[i] = data[(startPos + i) % capacity]
        }
        return result
    }
}
