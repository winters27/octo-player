package app.winters.octo.cast

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.winters.octo.output.DeviceShape
import app.winters.octo.output.OutputDevice
import app.winters.octo.output.OutputFamily
import app.winters.octo.output.OutputFinder
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Finds Cast devices through Android's media router, and connects to one
// through the Cast framework. Speaker groups show as devices of their own,
// which is how several speakers play at once, in step. Everything here
// runs on the main thread, as the router and the framework require. On a
// phone without Google Play services nothing is found.
@Singleton
class CastFinder @Inject constructor(@ApplicationContext private val context: Context) : OutputFinder {
    override val family = OutputFamily.Cast

    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private var cast: CastContext? = null
    private var loading = false
    private val waiting = mutableListOf<(CastContext) -> Unit>()

    private val _devices = MutableStateFlow<List<OutputDevice>>(emptyList())
    override val devices: StateFlow<List<OutputDevice>> = _devices

    private var events: OutputFinder.Events? = null
    private var looking = false
    private var pending: OutputDevice? = null
    private var output: CastOutput? = null

    override fun setEvents(events: OutputFinder.Events) {
        this.events = events
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            val device = pending ?: deviceOf(session)
            pending = null
            val connected = CastOutput(session, device) { stop -> cast?.sessionManager?.endCurrentSession(stop) }
            output = connected
            events?.connected(connected)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.i("Octo", "cast: could not connect: ${CastStatusCodes.getStatusCodeString(error)}")
            pending?.let { events?.failed(it) }
            pending = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val ended = output ?: return
            output = null
            events?.ended(ended, lost = error != CastStatusCodes.SUCCESS)
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    override fun startLooking() {
        looking = true
        withCast {
            if (!looking) return@withCast
            router.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refresh()
        }
    }

    override fun stopLooking() {
        looking = false
        if (cast != null) router.removeCallback(routes)
    }

    override fun connect(device: OutputDevice) {
        withCast(onFailure = { events?.failed(device) }) {
            val route = router.routes.firstOrNull { it.id == device.id }
            if (route == null) {
                events?.failed(device)
                return@withCast
            }
            pending = device
            router.selectRoute(route)
        }
    }

    // The Cast framework starts on first use, in the background.
    private fun withCast(onFailure: () -> Unit = {}, then: (CastContext) -> Unit) {
        cast?.let {
            then(it)
            return
        }
        waiting += then
        if (loading) return
        loading = true
        try {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { ready ->
                    loading = false
                    cast = ready
                    ready.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
                    waiting.toList().also { waiting.clear() }.forEach { it(ready) }
                }
                .addOnFailureListener { e ->
                    loading = false
                    waiting.clear()
                    Log.i("Octo", "cast: not available: ${e.javaClass.simpleName}")
                    onFailure()
                }
        } catch (e: Exception) {
            // No Google Play services on this phone.
            loading = false
            waiting.clear()
            Log.i("Octo", "cast: not available: ${e.javaClass.simpleName}")
            onFailure()
        }
    }

    private fun refresh() {
        _devices.value = router.routes
            .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map(::deviceOf)
            .sortedBy { it.name.lowercase() }
    }

    private fun deviceOf(route: MediaRouter.RouteInfo): OutputDevice {
        val cast = CastDevice.getFromBundle(route.extras)
        val group = route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_GROUP ||
            cast?.hasCapability(CastDevice.CAPABILITY_MULTIZONE_GROUP) == true
        val tv = route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV ||
            cast?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) == true
        return OutputDevice(
            id = route.id,
            name = route.name,
            family = OutputFamily.Cast,
            shape = when {
                group -> DeviceShape.Group
                tv -> DeviceShape.Tv
                else -> DeviceShape.Speaker
            },
            detail = if (group) "Group" else null,
        )
    }

    // A session that started without being asked for here, by its device.
    private fun deviceOf(session: CastSession): OutputDevice {
        val cast = session.castDevice
        return OutputDevice(
            id = router.selectedRoute.id,
            name = cast?.friendlyName ?: router.selectedRoute.name,
            family = OutputFamily.Cast,
            shape = DeviceShape.Speaker,
        )
    }
}
