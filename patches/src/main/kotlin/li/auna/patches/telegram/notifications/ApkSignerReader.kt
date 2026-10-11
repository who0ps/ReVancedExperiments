// SPDX-FileCopyrightText: 2026 OpenAI
// SPDX-License-Identifier: AGPL-3.0-or-later
package li.auna.patches.telegram.notifications

import java.io.File
import java.io.RandomAccessFile
import java.security.cert.CertificateFactory
import java.util.jar.JarFile

/** Reads signer certificates from APK Signature Scheme v2/v3/v3.1, with a JAR/v1 fallback. */
internal object ApkSignerReader {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private val signingBlockIds = listOf(0x1b93ad61L, 0xf05368c0L, 0x7109871aL)

    fun readSignerCertificates(apk: File): List<ByteArray> {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true)) {
            "Input is not an APK file: " + apk.absolutePath
        }
        val v2v3 = readApkSigningBlock(apk)
        if (v2v3.isNotEmpty()) return deduplicate(v2v3)
        val v1 = readV1Certificates(apk)
        if (v1.isNotEmpty()) return deduplicate(v1)
        throw IllegalArgumentException("No Android APK signing certificates found. Use the original, signed Telegram APK as input.")
    }

    private fun readApkSigningBlock(apk: File): List<ByteArray> = RandomAccessFile(apk, "r").use { raf ->
        val fileLength = raf.length()
        val eocd = findEocd(raf, fileLength)
        raf.seek(eocd + 16)
        val cdOffset = readUInt32LE(raf)
        require(cdOffset != 0xffffffffL) { "ZIP64 APKs are not supported" }
        require(cdOffset >= 24L && cdOffset < fileLength) { "Invalid APK central-directory offset" }
        raf.seek(cdOffset - 16)
        val magic = ByteArray(16)
        raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != APK_SIG_BLOCK_MAGIC) return@use emptyList()

        raf.seek(cdOffset - 24)
        val footerSize = readUInt64LE(raf)
        require(footerSize >= 24L && footerSize <= cdOffset - 8L) { "Invalid APK signing-block size" }
        val blockStart = cdOffset - footerSize - 8L
        raf.seek(blockStart)
        val headerSize = readUInt64LE(raf)
        require(headerSize == footerSize) { "APK signing-block header/footer size mismatch" }
        val pairsStart = blockStart + 8L
        val pairsEnd = cdOffset - 24L
        require(pairsStart <= pairsEnd) { "Invalid APK signing-block pair range" }

        val payloads = mutableMapOf<Long, ByteArray>()
        var position = pairsStart
        while (position < pairsEnd) {
            require(pairsEnd - position >= 8L) { "Truncated APK signing-block pair length" }
            raf.seek(position)
            val pairLength = readUInt64LE(raf)
            require(pairLength >= 4L && pairLength <= pairsEnd - position - 8L) { "Invalid APK signing-block pair length" }
            val id = readUInt32LE(raf)
            val valueLength = (pairLength - 4L).toInt()
            if (id in signingBlockIds) {
                val payload = ByteArray(valueLength)
                raf.readFully(payload)
                payloads[id] = payload
            }
            position += 8L + pairLength
        }
        for (id in signingBlockIds) {
            val payload = payloads[id] ?: continue
            val certs = runCatching { certificatesFromSchemeBlock(payload) }.getOrDefault(emptyList())
            if (certs.isNotEmpty()) return@use certs
        }
        emptyList()
    }

    private fun certificatesFromSchemeBlock(value: ByteArray): List<ByteArray> {
        val valueCursor = LittleEndianCursor(value)
        val signersBytes = valueCursor.readLengthPrefixed()
        valueCursor.requireEnd("scheme block")
        val signers = LittleEndianCursor(signersBytes)
        val result = mutableListOf<ByteArray>()
        while (signers.hasRemaining()) {
            val signerBytes = signers.readLengthPrefixed()
            val signer = LittleEndianCursor(signerBytes)
            val signedData = LittleEndianCursor(signer.readLengthPrefixed())
            signedData.readLengthPrefixed()
            val certs = LittleEndianCursor(signedData.readLengthPrefixed())
            if (certs.hasRemaining()) result += certs.readLengthPrefixed()
        }
        return result
    }

    private fun readV1Certificates(apk: File): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        JarFile(apk, true).use { jar ->
            val entries = jar.entries()
            val buffer = ByteArray(8192)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/", ignoreCase = true)) continue
                jar.getInputStream(entry).use { input ->
                    while (input.read(buffer) != -1) { /* Consume and verify signed entry. */ }
                }
                for (signer in entry.codeSigners ?: continue) {
                    signer.signerCertPath.certificates.firstOrNull()?.encoded?.let(result::add)
                }
            }
        }
        return result
    }

    private fun deduplicate(certs: List<ByteArray>): List<ByteArray> {
        val seen = linkedSetOf<String>()
        return certs.filter { seen.add(it.toHex()) }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        val digits = "0123456789abcdef"
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }

    private fun findEocd(raf: RandomAccessFile, length: Long): Long {
        require(length >= 22L) { "APK is too small to contain a ZIP EOCD record" }
        val lower = maxOf(0L, length - 22L - 0xffffL)
        var position = length - 22L
        while (position >= lower) {
            raf.seek(position)
            if (readUInt32LE(raf) == EOCD_SIGNATURE) {
                raf.seek(position + 20L)
                if (position + 22L + readUInt16LE(raf).toLong() == length) return position
            }
            position--
        }
        throw IllegalArgumentException("ZIP end-of-central-directory record was not found")
    }

    private fun readUInt16LE(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readUInt32LE(raf: RandomAccessFile): Long =
        raf.readUnsignedByte().toLong() or
            (raf.readUnsignedByte().toLong() shl 8) or
            (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 24)

    private fun readUInt64LE(raf: RandomAccessFile): Long {
        var value = 0L
        repeat(8) { shift -> value = value or (raf.readUnsignedByte().toLong() shl (shift * 8)) }
        require(value >= 0L) { "APK signing-block length is outside the supported range" }
        return value
    }

    private class LittleEndianCursor(private val bytes: ByteArray) {
        private var offset = 0
        fun hasRemaining() = offset < bytes.size

        fun readLengthPrefixed(): ByteArray {
            require(bytes.size - offset >= 4) { "Truncated length prefix in APK signing block" }
            val length = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            offset += 4
            require(length >= 0 && length <= bytes.size - offset) { "Invalid length in APK signing block" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun requireEnd(label: String) {
            require(offset == bytes.size) { "Unexpected trailing bytes in " + label }
        }
    }
}
