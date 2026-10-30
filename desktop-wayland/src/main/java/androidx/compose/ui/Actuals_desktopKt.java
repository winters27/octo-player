/*
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Native-host replacement for Compose 1.12.2's Actuals.desktop.kt.
 * That file contains only this getter, hardwired upstream to Skiko's Swing
 * dispatcher. RectManager's delayed defragmentation must run on the same
 * thread as layout. Dispatchers.Main is installed by WaylandDispatcherFactory.
 * Keep the application jar ahead of dependencies; DispatcherTest verifies both
 * the upstream ABI and real Compose delayed-callback dispatch.
 */
package androidx.compose.ui;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Dispatchers;

public final class Actuals_desktopKt {
    private Actuals_desktopKt() {}

    public static CoroutineContext getPostDelayedDispatcher() {
        return Dispatchers.getMain();
    }
}
