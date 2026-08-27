# Release-only rules. Applied on top of proguard-rules.pro, which both build types share.
#
# Strip informational logging.
#
# LimeLog calls android.util.Log directly, so the call itself is cheap. The cost this rule
# removes is at the call site: most of the LimeLog.info() call sites build their message by
# string concatenation, so the argument is allocated whether or not anything consumes it.
# Assuming info() has no side effects lets R8 drop the call and then dead-code-eliminate the
# StringBuilder chain feeding it.
#
# warning() and severe() are deliberately NOT stripped - they are what makes a field crash
# report readable, and there are far fewer of them.
#
# This lives here rather than in the shared file because it used to apply to debug builds too -
# minifyEnabled is set on both - which made a debug build silent in exactly the place you go
# looking when something is wrong. logStreamSummary(), the benchmark harness, is BuildConfig.DEBUG
# gated AND written with LimeLog.info(), so it emitted nothing in either build type for as long as
# it existed. HARDWARE_TESTING.md section 23 has the case that found it.
-assumenosideeffects class com.limelight.LimeLog {
    public static void info(java.lang.String);
}
