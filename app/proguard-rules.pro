# ============ BLive ProGuard / R8 规则 ============

# ---------- 日志剥离：release 包移除 Log.d/v 调用 ----------
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ---------- Gson 数据模型（@SerializedName + 反射字段访问） ----------
-keep class com.blive.tv.data.model.** { *; }
-keep class com.blive.tv.danmu.DanmuMessage { *; }
-keep class com.blive.tv.danmu.DanmuItem { *; }

# ---------- Retrofit / OkHttp ----------
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------- Kotlin 协程 ----------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ---------- ExoPlayer ----------
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ---------- Glide ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-dontwarn com.bumptech.glide.**

# ---------- Leanback / AppCompat ----------
-dontwarn androidx.leanback.**

# ---------- ZXing ----------
-dontwarn com.google.zxing.**

# ---------- Brotli ----------
-dontwarn org.brotli.**

# ---------- 通用 ----------
-keepattributes *Annotation*
-dontwarn javax.annotation.**
