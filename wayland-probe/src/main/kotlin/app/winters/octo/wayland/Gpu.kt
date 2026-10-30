package app.winters.octo.wayland

import org.jetbrains.desktop.linux.Application
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

// Called only inside WindowDraw, while KDT has made this window's EGL
// context current. No AWT surface, X display, or raster fallback is involved.
internal class Gpu(app: Application, logPrefix: String = "probe", allowSoftwareForTest: Boolean = false) : AutoCloseable {
    private val proc = checkNotNull(app.getEglProcFunc()) { "KDT did not provide EGL" }
    val renderer: String = glString(0x1f01)
    private val context: DirectContext
    private var target: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var size = 0 to 0

    init {
        check(renderer.isNotBlank()) { "GL_RENDERER is empty" }
        check(allowSoftwareForTest || !Regex("llvmpipe|softpipe|swrast|software|swiftshader", RegexOption.IGNORE_CASE).containsMatchIn(renderer)) {
            "Hardware GPU required; got $renderer"
        }
        println("$logPrefix: gpu=$renderer; gl=${glString(0x1f02)}")
        context = GLAssembledInterface.createFromNativePointers(proc.ctxPtr, proc.fPtr).use {
            DirectContext.makeGLWithInterface(it)
        }
        context.resourceCacheLimit = 64L * 1024 * 1024
    }

    private fun glString(name: Int): String = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val getProc = linker.downcallHandle(
            MemorySegment.ofAddress(proc.fPtr), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
        )
        val address = getProc.invokeWithArguments(
            MemorySegment.ofAddress(proc.ctxPtr), arena.allocateFrom("glGetString"),
        ) as MemorySegment
        check(address != MemorySegment.NULL) { "glGetString unavailable" }
        val getString = linker.downcallHandle(address, FunctionDescriptor.of(ADDRESS, JAVA_INT))
        val result = getString.invokeWithArguments(name) as MemorySegment
        check(result != MemorySegment.NULL) { "No current OpenGL context" }
        result.reinterpret(16_384).getString(0)
    }

    fun surface(width: Int, height: Int): Surface {
        if (size != width to height) {
            surface?.close()
            target?.close()
            target = BackendRenderTarget.makeGL(width, height, 1, 8, 0, FramebufferFormat.GR_GL_RGBA8)
            surface = checkNotNull(Surface.makeFromBackendRenderTarget(
                context, target!!, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
            )) { "Cannot wrap EGL framebuffer" }
            size = width to height
        }
        return surface!!
    }

    override fun close() {
        // Close requests can arrive while a different window's context is
        // current. Abandon first; EGL owns and destroys the GL resources.
        context.abandon()
        surface?.close()
        target?.close()
        context.close()
    }
}
