-keep class com.ahmer.pdfium.** { *; }
-dontwarn org.bouncycastle.**
-keepattributes *Annotation*

# sherpa-onnx Kotlin API talks to the bundled native JNI library by exact names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime Java API enters native code through JNI. Keep runtime bridge names in release builds.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PDF viewer / PDFBox-Android: release R8 must retain classes reached through reflection/JNI.
-keep class com.ahmer.pdfviewer.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# PDFBox-Android carries compatibility paths for desktop Java APIs that are not present on Android.
# They are not used by ClassHelper's Android code, so R8 may safely ignore those optional references.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn sun.awt.**
