package app.winters.octo.desktop.discord

import app.winters.octo.desktop.settings.DesktopOs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

// Talking to the Discord app running on this computer, the way its own
// libraries do: a named pipe on Windows (\\.\pipe\discord-ipc-0 to 9) and
// a socket file elsewhere. Every message is a frame: a number saying what
// it is and the length of what follows, both 4 bytes, least significant
// first, then JSON.

const val OP_HANDSHAKE = 0
const val OP_FRAME = 1
const val OP_CLOSE = 2
const val OP_PING = 3
const val OP_PONG = 4

// The most a frame from Discord may hold; anything bigger is not Discord.
private const val FRAME_LIMIT = 1 shl 20

data class IpcFrame(val op: Int, val body: String)

fun encodeFrame(op: Int, body: String): ByteArray {
    val bytes = body.encodeToByteArray()
    return ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(bytes.size).put(bytes).array()
}

// Bytes as they arrive, cut into frames.
class FrameBuffer {
    private var data = ByteArray(0)

    fun add(bytes: ByteArray, count: Int) {
        data += bytes.copyOf(count)
    }

    // The next whole frame, or null until one has arrived.
    fun take(): IpcFrame? {
        if (data.size < 8) return null
        val head = ByteBuffer.wrap(data, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val op = head.int
        val length = head.int
        if (length < 0 || length > FRAME_LIMIT) throw IOException("A frame of $length bytes")
        if (data.size < 8 + length) return null
        val body = data.decodeToString(8, 8 + length)
        data = data.copyOfRange(8 + length, data.size)
        return IpcFrame(op, body)
    }
}

// One end of the connection to Discord.
interface IpcPipe : Closeable {
    // Sends all of it.
    fun write(bytes: ByteArray)

    // Reads what has arrived without waiting: how many bytes, 0 when
    // nothing has, -1 once Discord has gone.
    fun readNow(into: ByteArray): Int
}

// Finds Discord and opens a pipe to it, or null when it is not running.
fun interface PipeOpener {
    fun open(): IpcPipe?
}

// Windows: a named pipe, read only as far as what has arrived (the JVM asks
// the pipe how much is waiting), so a quiet Discord never holds a thread.
class WindowsPipe(path: String) : IpcPipe {
    private val file = RandomAccessFile(path, "rw")
    private val waiting = FileInputStream(file.fd)

    override fun write(bytes: ByteArray) = file.write(bytes)

    override fun readNow(into: ByteArray): Int {
        val ready = waiting.available()
        if (ready <= 0) return 0
        return file.read(into, 0, minOf(ready, into.size))
    }

    override fun close() = file.close()
}

// macOS and Linux (and anywhere with socket files): a socket that never
// waits to read.
class SocketPipe(private val channel: SocketChannel) : IpcPipe {
    init {
        channel.configureBlocking(false)
    }

    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        val end = System.currentTimeMillis() + WRITE_WAIT_MS
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                if (System.currentTimeMillis() > end) throw IOException("Discord isn't reading")
                Thread.sleep(POLL_MS)
            }
        }
    }

    override fun readNow(into: ByteArray): Int = channel.read(ByteBuffer.wrap(into))

    override fun close() = channel.close()

    companion object {
        fun open(file: File): SocketPipe? =
            if (!file.exists()) null else runCatching { SocketPipe(SocketChannel.open(UnixDomainSocketAddress.of(file.toPath()))) }.getOrNull()
    }
}

// Where this system's Discord listens: the ten pipe names on Windows; the
// socket files in the usual folders elsewhere, including where the Flatpak
// and Snap builds of Discord put theirs.
fun discordPipes(os: DesktopOs, env: (String) -> String? = System::getenv): PipeOpener = when (os) {
    DesktopOs.Windows -> PipeOpener {
        (0..9).firstNotNullOfOrNull { n -> runCatching { WindowsPipe("""\\.\pipe\discord-ipc-$n""") }.getOrNull() }
    }
    else -> PipeOpener {
        socketFolders(env).firstNotNullOfOrNull { folder -> (0..9).firstNotNullOfOrNull { n -> SocketPipe.open(File(folder, "discord-ipc-$n")) } }
    }
}

// The folders a Discord socket file may be in, most likely first.
fun socketFolders(env: (String) -> String?): List<File> {
    val roots = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull(env).filter(String::isNotBlank) + "/tmp"
    val within = listOf("", "app/com.discordapp.Discord", "app/dev.vencord.Vesktop", ".flatpak/com.discordapp.Discord/xdg-run", "snap.discord", "snap.discord-canary")
    return roots.distinct().flatMap { root -> within.map { if (it.isEmpty()) File(root) else File(root, it) } }
}

// An open connection to Discord: the handshake, then setting and clearing
// the status. Discord answers each command, and the answer is read (up to
// a moment) so nothing piles up; its pings are answered.
class DiscordConnection(
    private val pipe: IpcPipe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : Closeable {
    private val buffer = FrameBuffer()
    private val chunk = ByteArray(4096)

    // Introduces Octo by its Discord application. True once Discord says
    // it is ready; false when it refuses (an unknown application) or says
    // nothing.
    fun handshake(appId: String, waitMs: Long = HANDSHAKE_WAIT_MS): Boolean {
        send(OP_HANDSHAKE, buildJsonObject {
            put("v", 1)
            put("client_id", appId)
        })
        val reply = receive(waitMs) ?: return false
        if (reply.op != OP_FRAME) return false
        return runCatching { Json.parseToJsonElement(reply.body).jsonObject["evt"]?.jsonPrimitive?.content == "READY" }.getOrDefault(false)
    }

    // Shows the activity, or clears the status with null. Throws when
    // Discord has gone or closed the connection.
    fun setActivity(activity: DiscordActivity?, pid: Long) {
        send(OP_FRAME, setActivityCommand(activity, pid, UUID.randomUUID().toString()))
        val reply = receive(ANSWER_WAIT_MS)
        if (reply?.op == OP_CLOSE) throw IOException("Discord closed the connection: ${reply.body}")
    }

    // The next frame that is not a ping, within the wait, or null.
    internal fun receive(waitMs: Long): IpcFrame? {
        val end = clock() + waitMs
        while (true) {
            val frame = buffer.take()
            if (frame != null) {
                if (frame.op == OP_PING) {
                    pipe.write(encodeFrame(OP_PONG, frame.body))
                    continue
                }
                return frame
            }
            val count = pipe.readNow(chunk)
            if (count < 0) throw IOException("Discord has gone")
            if (count > 0) {
                buffer.add(chunk, count)
                continue
            }
            if (clock() >= end) return null
            pause(POLL_MS)
        }
    }

    private fun send(op: Int, body: JsonObject) = pipe.write(encodeFrame(op, body.toString()))

    override fun close() {
        runCatching { pipe.write(encodeFrame(OP_CLOSE, "{}")) }
        runCatching { pipe.close() }
    }
}

// How long to wait for Discord: to greet Octo, and to answer a command.
const val HANDSHAKE_WAIT_MS = 3_000L
const val ANSWER_WAIT_MS = 1_500L
private const val WRITE_WAIT_MS = 2_000L
private const val POLL_MS = 15L
