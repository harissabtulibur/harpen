# HARPEN ProGuard / R8 Rules
-keepattributes *Annotation*
-keep class com.harpen.studio.model.** { *; }
-keepclassmembers class com.harpen.studio.model.** { *; }
-dontwarn org.bouncycastle.**
