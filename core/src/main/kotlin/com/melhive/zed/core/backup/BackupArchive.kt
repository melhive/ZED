package com.melhive.zed.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The backup file is damaged, cut short, or not a ZED backup. */
class BackupCorruptException(message: String) : IOException(message)

/** The password doesn't open the saved API keys. */
class WrongPasswordException : IOException("Wrong password")

/** First entry of every backup: what it holds, so a restore can be checked before anything is touched. */
@Serializable
data class BackupManifest(
    val format: Int = BackupFormat.VERSION,
    val appVersion: String = "",
    val dbVersion: Int = 0,
    val createdAt: Long = 0,
    val device: String = "",
    /** Names of the API keys and tokens saved in the (password-protected) keys entry. */
    val keyNames: List<String> = emptyList(),
    /** Model file names included. */
    val models: List<String> = emptyList(),
    /** Voice pack ids included. */
    val voices: List<String> = emptyList(),
    /** Bytes of data in all entries after the manifest. */
    val totalBytes: Long = 0,
    /** Row counts for the summary ("memories" → 12, …). */
    val counts: Map<String, Int> = emptyMap(),
) {
    fun encode(): ByteArray = JSON.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(bytes: ByteArray): BackupManifest = try {
            JSON.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCorruptException("The backup's summary can't be read.")
        }
    }
}

/**
 * ZED's backup container: a flat sequence of named entries, each followed by
 * its CRC-32, closed by an end marker and the entry count.
 *
 *     "ZEDBAK1\n"
 *     ( 0x01  name(UTF)  size(long)  bytes  crc32(long) )*
 *     0x00  entryCount(long)
 *
 * It streams (multi-gigabyte model files never sit in memory), has no 4 GB
 * limit, and a truncated or damaged file is always detected.
 */
object BackupFormat {
    const val VERSION = 1
    const val EXTENSION = "zedbackup"
    val MAGIC = "ZEDBAK1\n".toByteArray(Charsets.US_ASCII)

    const val MANIFEST = "manifest.json"
    const val KEYS = "keys.enc"
    const val SETTINGS = "settings/zed_settings.preferences_pb"
    const val DB = "db/zed.db"
    const val DB_WAL = "db/zed.db-wal"
    const val MODELS = "models/"
    const val VOICES = "voices/"

    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Entry names are relative paths of plain segments: they can never point outside the restore folder. */
    fun isSafeName(name: String): Boolean {
        val parts = name.split('/')
        return parts.size in 1..4 && parts.all { SEGMENT.matches(it) && !it.contains("..") }
    }
}

class BackupWriter(output: OutputStream) : Closeable {
    private val out = DataOutputStream(output)
    private var entries = 0L
    private var finished = false

    init { out.write(BackupFormat.MAGIC) }

    fun entry(name: String, bytes: ByteArray) = entry(name, bytes.size.toLong(), bytes.inputStream())

    /** Copies exactly [size] bytes from [input]; [onBytes] receives the number of bytes written since its last call. */
    fun entry(name: String, size: Long, input: InputStream, onBytes: (Long) -> Unit = {}) {
        require(BackupFormat.isSafeName(name)) { "Unsafe backup entry name: $name" }
        require(size >= 0)
        out.writeByte(1)
        out.writeUTF(name)
        out.writeLong(size)
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("$name ended ${left} bytes early while it was being backed up")
            out.write(buf, 0, n); crc.update(buf, 0, n)
            left -= n
            onBytes(n.toLong())
        }
        out.writeLong(crc.value)
        entries++
    }

    /** Writes the end marker. A backup without it is rejected on restore. */
    fun finish() {
        if (finished) return
        out.writeByte(0)
        out.writeLong(entries)
        out.flush()
        finished = true
    }

    override fun close() = out.close()
}

class BackupReader(input: InputStream) : Closeable {
    private val data = DataInputStream(input)
    private var current: EntryStream? = null
    private var seen = 0L
    private var ended = false

    class Entry(val name: String, val size: Long, val stream: InputStream) {
        fun readBytes(limit: Int = 8 shl 20): ByteArray {
            if (size > limit) throw BackupCorruptException("$name is larger than expected.")
            return stream.readBytes()
        }
    }

    init {
        val magic = ByteArray(BackupFormat.MAGIC.size)
        try { data.readFully(magic) } catch (e: EOFException) { throw BackupCorruptException("This isn't a ZED backup file.") }
        if (!magic.contentEquals(BackupFormat.MAGIC)) throw BackupCorruptException("This isn't a ZED backup file.")
    }

    /** The next entry, or null after the last one. Unread bytes of the previous entry are skipped and still checked. */
    fun next(): Entry? {
        if (ended) return null
        current?.drain()
        current = null
        try {
            when (data.readUnsignedByte()) {
                0 -> {
                    val count = data.readLong()
                    if (count != seen) throw BackupCorruptException("The backup lists $count parts but holds $seen.")
                    ended = true
                    return null
                }
                1 -> {
                    val name = data.readUTF()
                    val size = data.readLong()
                    if (!BackupFormat.isSafeName(name) || size < 0) throw BackupCorruptException("The backup contains an invalid entry.")
                    seen++
                    val stream = EntryStream(name, size)
                    current = stream
                    return Entry(name, size, stream)
                }
                else -> throw BackupCorruptException("The backup file is damaged.")
            }
        } catch (e: EOFException) {
            throw BackupCorruptException("The backup file is incomplete — it was cut short while being saved or copied.")
        } catch (e: java.io.UTFDataFormatException) {
            throw BackupCorruptException("The backup file is damaged.")
        }
    }

    private inner class EntryStream(val name: String, size: Long) : InputStream() {
        private var left = size
        private val crc = CRC32()
        private var checked = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left == 0L) { check(); return -1 }
            val n = data.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw BackupCorruptException("The backup file is incomplete — it was cut short while being saved or copied.")
            crc.update(b, off, n)
            left -= n
            if (left == 0L) check()
            return n
        }

        private fun check() {
            if (checked) return
            checked = true
            val expected = try { data.readLong() } catch (e: EOFException) {
                throw BackupCorruptException("The backup file is incomplete — it was cut short while being saved or copied.")
            }
            if (expected != crc.value) throw BackupCorruptException("$name in the backup is damaged.")
        }

        fun drain() {
            val buf = ByteArray(1 shl 16)
            while (read(buf, 0, buf.size) >= 0) { /* skipping still feeds the checksum */ }
        }
    }

    override fun close() = data.close()
}

/**
 * Password protection for the API keys inside a backup: AES-256-GCM with a key
 * derived by PBKDF2-HMAC-SHA256. Layout: salt[16] ‖ iv[12] ‖ ciphertext+tag.
 */
object BackupCrypto {
    private const val ITERATIONS = 120_000
    private const val SALT = 16
    private const val IV = 12

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val iv = ByteArray(IV).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (data.size < SALT + IV + 16) throw BackupCorruptException("The saved keys in the backup are damaged.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, data.copyOfRange(0, SALT)), GCMParameterSpec(128, data, SALT, IV))
        return try { cipher.doFinal(data, SALT + IV, data.size - SALT - IV) } catch (e: AEADBadTagException) { throw WrongPasswordException() }
    }

    private val KEYS = MapSerializer(String.serializer(), String.serializer())

    /** name → secret, as a compact JSON object. */
    fun encodeKeys(keys: Map<String, String>): ByteArray =
        Json.encodeToString(KEYS, keys).toByteArray(Charsets.UTF_8)

    fun decodeKeys(bytes: ByteArray): Map<String, String> = try {
        Json.decodeFromString(KEYS, String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        throw BackupCorruptException("The saved keys in the backup are damaged.")
    }
}
