# Swappy frame pacing (Android Game Development Kit).
#
# Godot statically links Swappy into libgodot_android.so. Swappy's native side
# resolves these two classes by exact name through the app class loader, and
# binds their members with JNI GetMethodID/RegisterNatives -- none of which R8
# can see. If they are shrunk, renamed, or have members removed, Swappy falls
# back to loading its own classes.dex out of the .so via InMemoryDexClassLoader,
# which hardened Android builds (GrapheneOS "DCL via memory") reject with a
# SecurityException that aborts the render thread inside GodotLib.step().
#
# Upstream equivalent: games-frame-pacing/extras/lib-proguard-rules.txt
-keep public class com.google.androidgamesdk.ChoreographerCallback { *; }
-keep public class com.google.androidgamesdk.SwappyDisplayManager { *; }
