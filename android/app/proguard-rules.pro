# kotlinx.serialization keeps its descriptors in generated companions; R8 will
# otherwise strip them and every @Serializable class fails at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.paladex.ex.core.**$$serializer { *; }
-keepclassmembers class com.paladex.ex.core.** {
    *** Companion;
}

# OkHttp ships optional references to Conscrypt/BouncyCastle providers.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup reflects over its node classes.
-keep class org.jsoup.** { *; }
