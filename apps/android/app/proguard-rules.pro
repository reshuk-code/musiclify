# Musiclify release shrinking rules.
# The app itself uses no reflection (JSON is org.json); these rules cover libraries that do.

# NewPipeExtractor runs YouTube's player JavaScript through Rhino, which loads classes by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# jsoup / nanojson pull in optional dependencies that aren't on Android.
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**

# Keep line numbers so crash reports from release builds are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
