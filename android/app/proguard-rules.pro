# uniffi + JNA: the generated bindings and JNA itself are found by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.lichat_core.** { *; }
-dontwarn java.awt.**
