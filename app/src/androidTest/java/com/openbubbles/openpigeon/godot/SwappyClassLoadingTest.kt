package com.openbubbles.openpigeon.godot

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Regression test for the GrapheneOS `memory_DCL` abort inside `GodotLib.step()`.
 *
 * Godot statically links Google's Swappy frame pacer and enables it by default
 * (`display/window/frame_pacing/android/enable_frame_pacing`). Swappy's native side
 * resolves its Java helpers with `gamesdk::loadClass()`, which asks the *activity's*
 * class loader first and, only when that throws, falls back to a `classes.dex` blob
 * linked into `libgodot_android.so` loaded through `dalvik.system.InMemoryDexClassLoader`.
 *
 * That fallback is dynamic code loading. Hardened Android builds reject it with a
 * `SecurityException`, and Swappy calls `CallObjectMethod` on the null result without
 * checking for the pending exception, so the render thread aborts.
 *
 * The property that keeps the fallback unreachable is exactly this: the names Swappy
 * passes must already resolve through our own class loader, with the members it binds
 * via `GetMethodID`/`RegisterNatives` intact. Note the *slash-separated* names -- that
 * is verbatim what Swappy passes to `ClassLoader.loadClass`, and ART accepts it because
 * `BaseDexClassLoader` only ever does `name.replace('.', '/')`.
 */
@RunWith(AndroidJUnit4::class)
class SwappyClassLoadingTest {

    private val classLoader: ClassLoader
        get() = InstrumentationRegistry.getInstrumentation().targetContext.classLoader

    /** `ChoreographerThread::CT_CLASS` in games-frame-pacing/common/ChoreographerThread.cpp. */
    @Test
    fun choreographerCallbackResolvesWithoutDynamicCodeLoading() {
        val cls = classLoader.loadClass("com/google/androidgamesdk/ChoreographerCallback")

        // JavaChoreographerThread::JavaChoreographerThread() binds these.
        cls.getConstructor(java.lang.Long.TYPE)
        cls.getMethod("postFrameCallback")
        cls.getMethod("terminate")

        // Registered natively by ChoreographerThread::CTNativeMethods.
        assertNative(
            cls.getDeclaredMethod(
                "nOnChoreographer", java.lang.Long.TYPE, java.lang.Long.TYPE
            )
        )
    }

    /**
     * `SwappyDisplayManager::SDM_CLASS`. This is the one that actually fires on current
     * Android: `SwappyCommon` gates the choreographer callback on the NDK choreographer
     * being unavailable, but gates the display manager on `usesMinSdkOrLater()` (SDK >= 28)
     * rather than `useSwappyDisplayManager()` (which excludes SDK >= 31), so it is
     * constructed on every modern device.
     */
    @Test
    fun swappyDisplayManagerResolvesWithoutDynamicCodeLoading() {
        val cls = classLoader.loadClass("com/google/androidgamesdk/SwappyDisplayManager")

        // SwappyDisplayManager::SwappyDisplayManager() binds these.
        cls.getConstructor(java.lang.Long.TYPE, Activity::class.java)
        cls.getMethod("setPreferredDisplayModeId", Integer.TYPE)
        cls.getMethod("terminate")

        // Registered natively by SwappyDisplayManager::SDMNativeMethods.
        assertNative(
            cls.getDeclaredMethod(
                "nSetSupportedRefreshPeriods",
                java.lang.Long.TYPE,
                LongArray::class.java,
                IntArray::class.java
            )
        )
        assertNative(
            cls.getDeclaredMethod(
                "nOnRefreshPeriodChanged",
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE
            )
        )
    }

    private fun assertNative(method: Method) {
        assertTrue(
            "${method.name} must stay native for RegisterNatives to bind it",
            Modifier.isNative(method.modifiers)
        )
    }
}
