# Add project specific ProGuard rules here.

# Keep USB serial classes
-keep class com.hoho.android.usbserial.** { *; }

# Keep Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes for serialization
-keep class com.canbox.manager.domain.model.** { *; }
-keep class com.canbox.manager.data.github.** { *; }
