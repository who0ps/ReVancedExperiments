package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** Parses the SafetyNet JWS payload (basicIntegrity / ctsProfileMatch) before requesting the Firebase SMS. */
internal val BytecodePatchContext.bypassIntegrityMethod by gettingFirstMethodDeclarativelyOrNull(
    "basicIntegrity", "ctsProfileMatch",
)

/**
 * Telegram 13.2.1 inlines the former AndroidUtilities helper into
 * ConnectionsManager.<init>(int). The DEX try table identifies the catch-all
 * handler whose fallback move writes the same local as the normal fingerprint
 * result. Resolve that register from the handler address instead of guessing
 * instruction adjacency.
 */
internal val BytecodePatchContext.signatureHashConstructorMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    definingClass("Lorg/telegram/tgnet/ConnectionsManager;")
    returnType("V")
    parameterTypes("I")
}
