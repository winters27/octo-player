@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.winters.octo.wayland

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DispatcherTest {
    @Test fun composeDelayedLayoutCallbacksUseTheHostDispatcher() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val actuals = Class.forName("androidx.compose.ui.Actuals_desktopKt")
            assertSame(Dispatchers.Main, actuals.getMethod("getPostDelayedDispatcher").invoke(null))
            val post = Class.forName("androidx.compose.ui.Actuals_skikoKt")
                .getMethod("postDelayed", Long::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
            val thread = Thread.currentThread()
            var called = false
            post.invoke(null, 16L, {
                assertSame(thread, Thread.currentThread())
                called = true
            })
            dispatcher.scheduler.runCurrent()
            assertFalse(called)
            dispatcher.scheduler.advanceTimeBy(17)
            assertTrue(called, "Compose's layout timer escaped the native main dispatcher")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun replacementMatchesTheSingleMethodUpstreamAbi() {
        val path = "androidx/compose/ui/Actuals_desktopKt.class"
        val upstream = javaClass.classLoader.getResources(path).toList().single { it.protocol == "jar" }
        val jar = (upstream.openConnection() as java.net.JarURLConnection).jarFileURL
        val name = "androidx.compose.ui.Actuals_desktopKt"
        object : URLClassLoader(arrayOf(jar), javaClass.classLoader) {
            override fun loadClass(className: String, resolve: Boolean): Class<*> =
                if (className == name) findClass(className) else super.loadClass(className, resolve)
        }.use { loader ->
            fun signatures(type: Class<*>) = type.declaredMethods.map {
                "${it.name}(${it.parameterTypes.joinToString { p -> p.name }}):${it.returnType.name}"
            }.sorted()
            assertEquals(signatures(loader.loadClass(name)), signatures(Class.forName(name)),
                "Compose's internal ABI changed; review the native dispatcher shim")
        }
    }
}
