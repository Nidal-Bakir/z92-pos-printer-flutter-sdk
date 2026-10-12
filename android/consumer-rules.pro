# The vendor JNI bridge uses reflection and class/member names inside native code.
-keep class com.zcs.** { *; }

# Only the SDK's optional smartcardio package references these Java SE types.
# DriverManager, Sys, and Printer do not reference it; this plugin exposes printing.
-dontwarn javax.smartcardio.ATR
-dontwarn javax.smartcardio.Card
-dontwarn javax.smartcardio.CardChannel
-dontwarn javax.smartcardio.CardTerminal
-dontwarn javax.smartcardio.CardTerminals$State
-dontwarn javax.smartcardio.CardTerminals
-dontwarn javax.smartcardio.CommandAPDU
-dontwarn javax.smartcardio.ResponseAPDU
-dontwarn javax.smartcardio.TerminalFactory
-dontwarn javax.smartcardio.TerminalFactorySpi
