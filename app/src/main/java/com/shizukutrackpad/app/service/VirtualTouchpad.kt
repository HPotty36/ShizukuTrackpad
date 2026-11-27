package com.shizukutrackpad.app.service

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A virtual HID precision touchpad created through /dev/uhid (the shell user is in the
 * `uhid` group, so this works from the Shizuku UserService).
 *
 * The kernel's hid-multitouch driver turns it into a real touchpad input device, so Android
 * handles it exactly like a physically connected one: system pointer, acceleration, tap to
 * click, two-finger scroll with fling, pinch, and three/four-finger gestures.
 */
class VirtualTouchpad(private val phys: String) {

    data class Contact(val id: Int, val x: Int, val y: Int, val tip: Boolean)

    private val writeLock = Any()

    @Volatile
    private var fd: FileDescriptor? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    var isStarted: Boolean = false
        private set

    val isOpen: Boolean
        get() = fd != null

    /** Opens /dev/uhid and registers the device. Throws on failure. */
    @Throws(ErrnoException::class)
    fun create() {
        if (fd != null) return
        val opened = Os.open(UHID_PATH, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        fd = opened
        // The kernel asks for feature reports while probing, so the reader must run first.
        readerThread = Thread({ readLoop(opened) }, "UhidTouchpadReader").apply {
            isDaemon = true
            start()
        }
        try {
            writeEvent(buildCreate2())
        } catch (e: ErrnoException) {
            destroy()
            throw e
        }
    }

    /** Waits until the kernel has started the device (UHID_START). */
    fun awaitStarted(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!isStarted && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20L)
        }
        return isStarted
    }

    /**
     * Sends one input frame. [contacts] lists every finger on the surface plus, once, each
     * finger that just lifted (tip = false). [buttons] bit 0 = left, 1 = right, 2 = middle.
     */
    fun sendFrame(contacts: List<Contact>, buttons: Int) {
        val report = ByteArray(INPUT_REPORT_SIZE)
        report[0] = REPORT_ID_INPUT.toByte()
        val count = contacts.size.coerceAtMost(MAX_CONTACTS)
        for (i in 0 until count) {
            val c = contacts[i]
            val base = 1 + i * FINGER_BYTES
            val x = c.x.coerceIn(0, LOGICAL_MAX)
            val y = c.y.coerceIn(0, LOGICAL_MAX)
            // bit0 confidence, bit1 tip switch, bits2-4 contact id
            report[base] = (0x01 or (if (c.tip) 0x02 else 0) or ((c.id and 0x07) shl 2)).toByte()
            report[base + 1] = (x and 0xFF).toByte()
            report[base + 2] = (x shr 8).toByte()
            report[base + 3] = (y and 0xFF).toByte()
            report[base + 4] = (y shr 8).toByte()
        }
        val tail = 1 + MAX_CONTACTS * FINGER_BYTES
        // Scan time in 100 µs units, wrapping at 16 bits.
        val scanTime = ((SystemClock.uptimeMillis() * 10) and 0xFFFF).toInt()
        report[tail] = (scanTime and 0xFF).toByte()
        report[tail + 1] = (scanTime shr 8).toByte()
        report[tail + 2] = count.toByte()
        report[tail + 3] = (buttons and 0x07).toByte()

        val event = ByteBuffer.allocate(4 + 2 + report.size).order(ByteOrder.LITTLE_ENDIAN)
        event.putInt(UHID_INPUT2)
        event.putShort(report.size.toShort())
        event.put(report)
        runCatching { writeEvent(event.array()) }
            .onFailure { Log.w(TAG, "Touchpad report failed: ${it.message}") }
    }

    fun destroy() {
        val current = fd ?: return
        runCatching {
            writeEvent(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array())
        }
        fd = null
        isStarted = false
        runCatching { Os.close(current) }
        readerThread?.interrupt()
        readerThread = null
    }

    private fun writeEvent(bytes: ByteArray) {
        val target = fd ?: throw ErrnoException("write", OsConstants.EBADF)
        synchronized(writeLock) {
            var offset = 0
            while (offset < bytes.size) {
                offset += Os.write(target, bytes, offset, bytes.size - offset)
            }
        }
    }

    private fun readLoop(source: FileDescriptor) {
        val buffer = ByteArray(UHID_EVENT_SIZE)
        while (fd === source) {
            val read = try {
                Os.read(source, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                if (fd === source) Log.w(TAG, "uhid read failed: ${e.message}")
                break
            }
            if (read < 4) break
            val event = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            when (event.getInt(0)) {
                UHID_START -> isStarted = true
                UHID_STOP -> isStarted = false
                UHID_GET_REPORT -> {
                    val id = event.getInt(4)
                    val reportNumber = buffer[8].toInt() and 0xFF
                    answerGetReport(id, reportNumber)
                }
                UHID_SET_REPORT -> {
                    val id = event.getInt(4)
                    val reply = ByteBuffer.allocate(4 + 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
                    reply.putInt(UHID_SET_REPORT_REPLY).putInt(id).putShort(0)
                    runCatching { writeEvent(reply.array()) }
                }
                else -> Unit // OPEN / CLOSE / OUTPUT need no answer
            }
        }
    }

    private fun answerGetReport(id: Int, reportNumber: Int) {
        val data: ByteArray? = when (reportNumber) {
            // Contact count maximum (low nibble) + pad type 2 = touchpad with separate buttons
            REPORT_ID_CAPABILITIES -> byteArrayOf(REPORT_ID_CAPABILITIES.toByte(), (0x20 or MAX_CONTACTS).toByte())
            // Input mode 3 = touchpad
            REPORT_ID_INPUT_MODE -> byteArrayOf(REPORT_ID_INPUT_MODE.toByte(), 0x03)
            // Surface + button reporting both on
            REPORT_ID_FUNCTION_SWITCH -> byteArrayOf(REPORT_ID_FUNCTION_SWITCH.toByte(), 0x03)
            else -> null
        }
        val size = data?.size ?: 0
        val reply = ByteBuffer.allocate(4 + 4 + 2 + 2 + size).order(ByteOrder.LITTLE_ENDIAN)
        reply.putInt(UHID_GET_REPORT_REPLY)
        reply.putInt(id)
        reply.putShort((if (data == null) OsConstants.EIO else 0).toShort())
        reply.putShort(size.toShort())
        if (data != null) reply.put(data)
        runCatching { writeEvent(reply.array()) }
    }

    private fun buildCreate2(): ByteArray {
        val descriptor = REPORT_DESCRIPTOR
        val event = ByteBuffer.allocate(CREATE2_HEADER_SIZE + descriptor.size).order(ByteOrder.LITTLE_ENDIAN)
        event.putInt(UHID_CREATE2)
        event.put(fixedString(DEVICE_NAME, 128))
        event.put(fixedString(phys, 64))
        event.put(fixedString("", 64))
        event.putShort(descriptor.size.toShort())
        event.putShort(BUS_USB.toShort())
        event.putInt(VENDOR_ID)
        event.putInt(PRODUCT_ID)
        event.putInt(1) // version
        event.putInt(0) // country
        event.put(descriptor)
        return event.array()
    }

    private fun fixedString(value: String, length: Int): ByteArray {
        val out = ByteArray(length)
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, out, 0, bytes.size.coerceAtMost(length - 1))
        return out
    }

    companion object {
        private const val TAG = "VirtualTouchpad"
        private const val UHID_PATH = "/dev/uhid"

        /** Kernel appends " Touchpad" for the touchpad application collection. */
        const val DEVICE_NAME = "ShizukuTrackpad"
        const val VENDOR_ID = 0x1209
        const val PRODUCT_ID = 0x7470
        private const val BUS_USB = 0x03

        /** Fingers per report. Android's gestures need up to four; five leaves room. */
        const val MAX_CONTACTS = 5

        /** Both axes span 0..LOGICAL_MAX over [PHYSICAL_SIZE_MM] (about 25.6 units per mm). */
        const val LOGICAL_MAX = 4095
        const val PHYSICAL_SIZE_MM = 160f

        // linux/uhid.h event types
        private const val UHID_DESTROY = 1
        private const val UHID_START = 2
        private const val UHID_STOP = 3
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14

        /** type(4) + name(128) + phys(64) + uniq(64) + rd_size(2) + bus(2) + vendor/product/version/country(16) */
        private const val CREATE2_HEADER_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 16

        /** sizeof(struct uhid_event): type + the largest union member (create2). */
        private const val UHID_EVENT_SIZE = CREATE2_HEADER_SIZE + 4096

        private const val REPORT_ID_INPUT = 0x01
        private const val REPORT_ID_CAPABILITIES = 0x02
        private const val REPORT_ID_INPUT_MODE = 0x03
        private const val REPORT_ID_FUNCTION_SWITCH = 0x04

        private const val FINGER_BYTES = 5
        /** report id + fingers + scan time(2) + contact count(1) + buttons(1) */
        private const val INPUT_REPORT_SIZE = 1 + MAX_CONTACTS * FINGER_BYTES + 4

        /** Windows-precision-touchpad style descriptor (hid-multitouch understands it). */
        val REPORT_DESCRIPTOR: ByteArray = buildDescriptor()

        private fun buildDescriptor(): ByteArray {
            val out = ByteArrayOutputStream()
            fun b(vararg values: Int) = values.forEach { out.write(it) }
            // Physical max 1600 = 16.00 cm with unit exponent -2 (cm)
            val physicalMax = (PHYSICAL_SIZE_MM * 10).toInt()

            b(0x05, 0x0D)             // Usage Page (Digitizer)
            b(0x09, 0x05)             // Usage (Touch Pad)
            b(0xA1, 0x01)             // Collection (Application)
            b(0x85, REPORT_ID_INPUT)  //   Report ID
            repeat(MAX_CONTACTS) {
                b(0x09, 0x22)         //   Usage (Finger)
                b(0xA1, 0x02)         //   Collection (Logical)
                b(0x15, 0x00, 0x25, 0x01)
                b(0x09, 0x47, 0x09, 0x42)             // Confidence, Tip Switch
                b(0x95, 0x02, 0x75, 0x01, 0x81, 0x02) // 2 x 1 bit, Input
                b(0x25, 0x07, 0x09, 0x51)             // Contact Identifier 0..7
                b(0x75, 0x03, 0x95, 0x01, 0x81, 0x02) // 3 bits, Input
                b(0x75, 0x03, 0x95, 0x01, 0x81, 0x03) // 3 bits padding
                b(0x05, 0x01)                         // Usage Page (Generic Desktop)
                b(0x15, 0x00, 0x26, LOGICAL_MAX and 0xFF, LOGICAL_MAX shr 8)
                b(0x75, 0x10)                         // 16 bits
                b(0x55, 0x0E, 0x65, 0x11)             // Unit exponent -2, unit cm
                b(0x35, 0x00, 0x46, physicalMax and 0xFF, physicalMax shr 8)
                b(0x09, 0x30, 0x95, 0x01, 0x81, 0x02) // X
                b(0x09, 0x31, 0x81, 0x02)             // Y
                b(0x05, 0x0D)                         // back to Digitizer page
                b(0xC0)               //   End Collection
            }
            b(0x55, 0x0C, 0x66, 0x01, 0x10)           // Unit exponent -4, unit seconds
            b(0x47, 0xFF, 0xFF, 0x00, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00)
            b(0x75, 0x10, 0x95, 0x01, 0x09, 0x56, 0x81, 0x02) // Scan Time
            b(0x65, 0x00, 0x55, 0x00, 0x35, 0x00, 0x45, 0x00) // reset units
            b(0x09, 0x54, 0x15, 0x00, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02) // Contact Count
            b(0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x25, 0x01) // Buttons 1..3
            b(0x75, 0x01, 0x95, 0x03, 0x81, 0x02)
            b(0x95, 0x05, 0x81, 0x03)                 // 5 bits padding
            b(0x05, 0x0D, 0x85, REPORT_ID_CAPABILITIES)
            b(0x09, 0x55, 0x09, 0x59)                 // Contact Count Maximum, Pad Type
            b(0x75, 0x04, 0x95, 0x02, 0x25, 0x0F, 0xB1, 0x02) // Feature
            b(0xC0)                   // End Collection

            b(0x05, 0x0D, 0x09, 0x0E) // Usage (Device Configuration)
            b(0xA1, 0x01)
            b(0x85, REPORT_ID_INPUT_MODE)
            b(0x09, 0x22, 0xA1, 0x02)
            b(0x09, 0x52, 0x15, 0x00, 0x25, 0x0A, 0x75, 0x08, 0x95, 0x01, 0xB1, 0x02) // Input Mode
            b(0xC0)
            b(0x09, 0x22, 0xA1, 0x00)
            b(0x85, REPORT_ID_FUNCTION_SWITCH)
            b(0x09, 0x57, 0x09, 0x58, 0x75, 0x01, 0x95, 0x02, 0x25, 0x01, 0xB1, 0x02) // Surface/Button switch
            b(0x95, 0x06, 0xB1, 0x03)
            b(0xC0)
            b(0xC0)
            return out.toByteArray()
        }
    }
}
