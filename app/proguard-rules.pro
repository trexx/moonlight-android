# Don't obfuscate code. Stack traces from user crash reports have to be readable without a
# mapping file, since builds are made from source by anyone running this fork.
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
# The USB drivers are reached from native code (the xow driver calls back into
# XboxWirelessDongle and XboxWirelessController by name), so R8 cannot see those references.
-keep class com.limelight.binding.input.driver.* {*;}

# Moonlight common
# MoonBridge's methods are resolved by name from JNI; shrinking them breaks every callback.
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
# JCA providers are instantiated reflectively by algorithm name, so nothing here is reachable
# through a call graph R8 can follow.
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**
