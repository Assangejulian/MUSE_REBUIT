# Debug first release; keep rules ready for later minify.
-keepattributes *Annotation*
-keep class com.muse.** { *; }
-keep class com.muse.app.MuseShellService { *; }
-keep class com.muse.app.IMuseShell { *; }
-keep class com.muse.app.IMuseShell\$Stub { *; }
