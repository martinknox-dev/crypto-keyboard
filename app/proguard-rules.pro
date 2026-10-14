# Disable obfuscation (we use Proguard exclusively for optimization)
-dontobfuscate

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Crypto engine is accessed exclusively via reflection from CryptoBarHook
# (Class.forName + getMethod), so R8 must not strip or rename these.
# See CryptoBarHook.initialize() for the full list of reflective lookups.
-keep class com.mkcreations.cryptokeyboard.** { *; }
-keepclassmembers class com.mkcreations.cryptokeyboard.** { *; }

# ML Kit resolves its internal components via reflection (firebase-components DI:
# the manifest registers BarcodeRegistrar/VisionCommonRegistrar/CommonComponentRegistrar
# and they are instantiated by Class.forName + newInstance; LazyInstanceMap factories
# are looked up reflectively too). R8 full mode (default with AGP 9) strips their
# constructors and the BarcodeScanning entry class, which NPEs the moment the QR
# scanner calls BarcodeScanning.getClient(). Known issue: keep the whole ML Kit
# surface plus registrar constructors.
# See https://github.com/googlesamples/mlkit/issues/1018
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
