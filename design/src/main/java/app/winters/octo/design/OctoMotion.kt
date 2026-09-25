package app.winters.octo.design

import androidx.compose.animation.core.spring

// Shared springs so every surface moves the same way.
object OctoMotion {
    fun <T> soft() = spring<T>(dampingRatio = 0.75f, stiffness = 400f)
    fun <T> snappy() = spring<T>(dampingRatio = 0.65f, stiffness = 600f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
    fun <T> jelly() = spring<T>(dampingRatio = 0.55f, stiffness = 300f)
    fun <T> smooth() = spring<T>(dampingRatio = 0.75f, stiffness = 1500f)
}
