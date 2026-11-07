package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** Parses the SafetyNet JWS payload (basicIntegrity / ctsProfileMatch) before requesting the Firebase SMS. */
internal val BytecodePatchContext.bypassIntegrityMethod by gettingFirstMethodDeclarativelyOrNull(
    "basicIntegrity", "ctsProfileMatch",
)

/**
 * Telegram 13.2.1 inlines the former AndroidUtilities helper into
 * ConnectionsManager.<init>(int). The SHA-256 result is moved into a
 * single register immediately before TimeZone.getDefault().
 */
internal val BytecodePatchContext.signatureHashConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lorg/telegram/tgnet/ConnectionsManager;")
    returnType("V")
    parameterTypes("I")
}
