/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Vendored verbatim from the Android Game Development Kit (AGDK), Swappy frame
// pacing "extras" module:
//   frameworks/opt/gamesdk/games-frame-pacing/extras/src/main/java/
//       com/google/androidgamesdk/ChoreographerCallback.java
//
// WHY THIS FILE EXISTS IN AN APP THAT NEVER CALLS IT DIRECTLY:
//
// Godot statically links Swappy into libgodot_android.so and enables it by
// default (display/window/frame_pacing/android/enable_frame_pacing). Swappy's
// native side resolves its Java helpers with gamesdk::loadClass(), which:
//
//   1. tries activity.getClassLoader().loadClass("com/google/androidgamesdk/X")
//   2. and ONLY on failure falls back to a classes.dex blob linked into the
//      .so, loaded via dalvik.system.InMemoryDexClassLoader.
//
// Step 2 is dynamic code loading. Hardened Android builds (e.g. GrapheneOS with
// the per-app "DCL via memory" restriction) throw SecurityException from
// InMemoryDexClassLoader's constructor; Swappy does not check for a pending
// exception before its next JNI call, so the process aborts on the render
// thread inside GodotLib.step().
//
// Shipping these classes in our own APK makes step 1 succeed, so the in-memory
// DEX path is never taken. Frame pacing keeps working, on stock and hardened
// Android alike. Do not rename, move, or shrink these classes -- Swappy looks
// them up by exact name and binds their members via JNI RegisterNatives.
// See app/proguard-rules.pro and SwappyClassLoadingTest.

package com.google.androidgamesdk;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.util.Log;


public class ChoreographerCallback implements Choreographer.FrameCallback {
    private static final String LOG_TAG = "ChoreographerCallback";
    private long mCookie;
    private LooperThread mLooper;

    private class LooperThread extends Thread {
        public Handler mHandler;

        public void run() {
            Log.i(LOG_TAG, "Starting looper thread");
            Looper.prepare();
            mHandler = new Handler();
            Looper.loop();
            Log.i(LOG_TAG, "Terminating looper thread");
        }
    }

    public ChoreographerCallback(long cookie) {
        mCookie = cookie;
        mLooper = new LooperThread();
        mLooper.start();
    }

    public void postFrameCallback() {
        mLooper.mHandler.post(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(ChoreographerCallback.this);
            }
        });
    }

    public void postFrameCallbackDelayed(long delayMillis) {
        Choreographer.getInstance().postFrameCallbackDelayed(this, delayMillis);
    }

    public void terminate() {
        mLooper.mHandler.getLooper().quit();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        nOnChoreographer(mCookie, frameTimeNanos);
    }

    public native void nOnChoreographer(long cookie, long frameTimeNanos);

}
