# JNA finds native methods, structures and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# UniFFI bindings: JNA maps these names to the Rust library's symbols.
-keep class com.jurisgames.papeles.core.** { *; }
