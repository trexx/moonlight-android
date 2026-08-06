# Don't obfuscate code
-dontobfuscate

# Strip informational logging from release builds.
#
# LimeLog wraps java.util.logging, which bridges to Log through a handler chain that takes
# locks on every call. 181 of the 242 call sites are LimeLog.info() and most build their
# message by string concatenation, so the argument is allocated whether or not anything
# consumes it. Assuming info() has no side effects lets R8 drop the call and then
# dead-code-eliminate the StringBuilder chain feeding it.
#
# warning() and severe() are deliberately NOT stripped - they are what makes a field crash
# report readable, and there are only 61 of them.
-assumenosideeffects class com.limelight.LimeLog {
    public static void info(java.lang.String);
}

# Our code
-keep class com.limelight.binding.input.driver.* {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**
