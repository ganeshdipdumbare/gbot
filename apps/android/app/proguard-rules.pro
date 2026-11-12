# kotlinx.serialization keeps its generated serializers via its own rules;
# these keep the @Serializable classes' companions reachable.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class dev.gbot.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.commonmark.ext.**

# LiteRT-LM's native library finds its Kotlin/Java counterparts by name.
-keep class com.google.ai.edge.litertlm.** { *; }
# LiteRT-LM converts tool arguments through Gson.
-keep class com.google.gson.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
