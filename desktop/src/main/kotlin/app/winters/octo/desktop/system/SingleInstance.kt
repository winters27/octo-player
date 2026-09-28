package app.winters.octo.desktop.system

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

// One Octo at a time. The first to start holds a lock file in the settings
// folder and listens on a port of this machine's own loopback address,
// written beside the lock with a secret only the same user can read.
// Starting Octo again finds the lock taken, sends its command line (files
// to play, octo:// links) with the secret to the running one, and ends; the
// running one comes forward and plays them.
//
// A loopback port rather than a Unix socket file: those fail on some file
// systems (Windows' redirected app folders among them), and a port works
// the same on all three systems.
class SingleInstance private constructor(
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val secret: ByteArray,
    private val addressFile: File,
) : AutoCloseable {
    private val guard = Any()
    private var listener: ((List<String>) -> Unit)? = null
    private val waiting = ArrayList<List<String>>()

    @Volatile
    private var open = true

    private val acceptor = Thread({ acceptLoop() }, "octo-instance").apply {
        isDaemon = true
        start()
    }

    // Hears each later launch's command line, on the listening thread. Any
    // that came before a listener was set are handed over at once.
    fun onLaunch(listener: (List<String>) -> Unit) {
        val backlog = synchronized(guard) {
            this.listener = listener
            waiting.toList().also { waiting.clear() }
        }
        backlog.forEach(listener)
    }

    private fun acceptLoop() {
        while (open) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            runCatching {
                client.use { socket ->
                    socket.soTimeout = READ_TIMEOUT_MS
                    val input = DataInputStream(socket.getInputStream())
                    val args = readMessage(input, secret) ?: return@use
                    socket.getOutputStream().apply {
                        write(ACK.toInt())
                        flush()
                    }
                    val target = synchronized(guard) { listener.also { if (it == null) waiting += args } }
                    target?.invoke(args)
                }
            }
        }
    }

    override fun close() {
        open = false
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(addressFile.toPath()) }
        runCatching { lock.release() }
        runCatching { lockChannel.close() }
        runCatching { acceptor.join(1000) }
    }

    // How a start went.
    sealed interface Claim {
        // This is the only Octo; it listens for later launches.
        class First(val instance: SingleInstance) : Claim

        // Another Octo is running and took this launch's command line.
        data object HandedOver : Claim

        // Could not tell, or could not reach the running one; this one runs
        // on its own rather than not at all.
        data class Alone(val reason: String) : Claim
    }

    companion object {
        private const val MAGIC = 0x4F43544F // "OCTO"
        private const val VERSION = 1
        private const val ACK: Byte = 1
        private const val SECRET_BYTES = 32
        private const val READ_TIMEOUT_MS = 3_000
        const val LOCK_FILE = "instance.lock"
        const val ADDRESS_FILE = "instance.port"

        // Tries to be the one Octo. `beforeHandover` runs just before this
        // launch hands over, on Windows to let the running one come forward.
        fun claim(
            folder: File,
            args: List<String>,
            waitMs: Long = 3_000,
            beforeHandover: () -> Unit = {},
        ): Claim {
            try {
                folder.mkdirs()
                val addressFile = File(folder, ADDRESS_FILE)
                val channel = FileChannel.open(File(folder, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return listen(channel, lock, addressFile)
                channel.close()
                beforeHandover()
                // The running one may hold the lock but not yet listen.
                val until = System.currentTimeMillis() + waitMs
                var last: Exception? = null
                while (System.currentTimeMillis() < until) {
                    try {
                        if (handOver(addressFile, args)) return Claim.HandedOver
                    } catch (e: IOException) {
                        last = e
                    }
                    Thread.sleep(100)
                }
                return Claim.Alone("the running Octo did not answer: ${last?.message ?: "no address yet"}")
            } catch (e: IOException) {
                return Claim.Alone("could not check for a running Octo: ${e.message}")
            }
        }

        private fun listen(channel: FileChannel, lock: FileLock, addressFile: File): Claim {
            val server = try {
                ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            } catch (e: IOException) {
                lock.release()
                channel.close()
                return Claim.Alone("could not listen for other launches: ${e.message}")
            }
            val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            try {
                writePrivately(addressFile, "${server.localPort} ${secret.toHex()}")
            } catch (e: IOException) {
                server.close()
                lock.release()
                channel.close()
                return Claim.Alone("could not note where to find this Octo: ${e.message}")
            }
            return Claim.First(SingleInstance(channel, lock, server, secret, addressFile))
        }

        // Writes the file readable by this user only where the system has
        // such permissions (the settings folder is private on Windows).
        private fun writePrivately(file: File, text: String) {
            val temp = File(file.parentFile, file.name + ".tmp")
            Files.deleteIfExists(temp.toPath())
            runCatching { Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            temp.writeText(text)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        private fun handOver(addressFile: File, args: List<String>): Boolean {
            val (port, secret) = readAddress(addressFile) ?: return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), READ_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(encodeMessage(args, secret))
                    flush()
                }
                return socket.getInputStream().read() == ACK.toInt()
            }
        }

        // The port and secret from the address file, or null when there is
        // none or it makes no sense.
        fun readAddress(file: File): Pair<Int, ByteArray>? {
            val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return null
            val port = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            val secret = parts.getOrNull(1)?.fromHex()?.takeIf { it.size == SECRET_BYTES } ?: return null
            return port to secret
        }

        // A launch's command line as sent: a mark, a version, the secret, a
        // count and each argument as UTF-8.
        fun encodeMessage(args: List<String>, secret: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.write(secret)
                out.writeInt(args.size)
                args.forEach { arg ->
                    val utf = arg.toByteArray()
                    out.writeInt(utf.size)
                    out.write(utf)
                }
            }
            return bytes.toByteArray()
        }

        // The command line from a message, or null for anything that is not
        // one: a stray connection, the wrong secret, a newer version, too much.
        fun readMessage(input: DataInputStream, secret: ByteArray): List<String>? = try {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                null
            } else {
                val sent = ByteArray(secret.size).also(input::readFully)
                val count = input.readInt()
                if (!MessageDigest.isEqual(sent, secret) || count !in 0..1000) {
                    null
                } else {
                    List(count) {
                        val size = input.readInt()
                        if (size !in 0..65_536) throw IOException("argument too long")
                        ByteArray(size).also(input::readFully).decodeToString()
                    }
                }
            }
        } catch (e: IOException) {
            null
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? =
            if (length % 2 != 0) null else runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }
}
