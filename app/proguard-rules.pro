# JNI exports resolve these Java class and native method names dynamically.
-keepclasseswithmembers,includedescriptorclasses class dev.aarchive.NativeArchive {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class dev.aarchive.NativeWriter {
    native <methods>;
}

# bridge.cpp creates entries through this exact class/constructor signature.
-keep class dev.aarchive.ArchiveEntry {
    public <init>(java.lang.String, long, int, boolean);
}

# Native cancellation checks use GetFieldID on these volatile fields.
-keepclassmembers class dev.aarchive.NativeArchive$Cancellation {
    private boolean cancelled;
}
-keepclassmembers class dev.aarchive.NativeWriter {
    private boolean cancelled;
}
