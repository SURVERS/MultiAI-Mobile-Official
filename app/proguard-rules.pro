# Add project specific ProGuard rules here.
-keep class app.multiai.mobile.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Gson TypeToken — R8 must keep generic signatures or TypeToken.<init> throws.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ML Kit Barcode Scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.vision.** { *; }
