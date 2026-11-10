# LiteRT-LM uses JNI and reflection (Gson, kotlin-reflect for tools).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.melhive.zenith.** { *** Companion; }
-keepclasseswithmembers class com.melhive.zenith.** { kotlinx.serialization.KSerializer serializer(...); }
# Room entities and WorkManager workers are kept by their own consumer rules.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# sherpa-onnx: native code reads config fields and calls back by name over JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# commons-compress optional codecs (zstd, brotli, xz) are not used.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
