# Hardkey D0/D1: every class is reachable from the manifest-registered
# HardkeyInit provider chain, but keep the package explicitly so R8 full-mode
# can never strip the runtime-attach chain in consumer apps.
-keep class org.telegram.hardkey.** { *; }
