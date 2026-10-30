# Only included with -PupdaterTests=true by the disposable-emulator test script.
# Its separate instrumentation APK calls these internal APIs and reflects on the
# updater to supply mock responses. Published APKs do not include these rules.
-keep class dev.aarchive.UpdatePolicy { *; }
-keep class dev.aarchive.UpdateClient { *; }
-keep class dev.aarchive.UpdateClient$* { *; }
-keep class dev.aarchive.UpdateVerifier { *; }
-keep class dev.aarchive.Updater { *; }
-keep class dev.aarchive.Updater$* { *; }
-keep class dev.aarchive.ArchiveModel {
    dev.aarchive.Updater updater;
}
-keepclassmembers class dev.aarchive.MainActivity {
    dev.aarchive.ArchiveModel model;
}
