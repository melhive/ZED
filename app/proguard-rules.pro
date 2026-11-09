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
