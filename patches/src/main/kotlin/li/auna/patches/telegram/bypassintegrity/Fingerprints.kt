package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** Parses the SafetyNet JWS payload (basicIntegrity / ctsProfileMatch) before requesting the Firebase SMS. */
internal val BytecodePatchContext.bypassIntegrityMethod by gettingFirstMethodDeclarativelyOrNull(
    "basicIntegrity", "ctsProfileMatch",
)

internal val BytecodePatchContext.spoofSignatureMethod by gettingFirstMethodDeclarativelyOrNull {
    name("getCertificateSHA256Fingerprint")
    definingClass("Lorg/telegram/messenger/AndroidUtilities;")
    returnType("Ljava/lang/String;")
}
