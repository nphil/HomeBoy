# Keep all Outlined Material Icons so reflection-based icon search works in release builds
-keep class androidx.compose.material.icons.outlined.** { *; }

# Gson reflection on API models
-keep class com.homeboy.app.api.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes AnnotationDefault
-keepattributes InnerClasses, EnclosingMethod

# Retrofit (suspend functions + R8 full mode)
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRE

# ONNX Runtime (JNI-backed; keep its classes and native bindings)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
