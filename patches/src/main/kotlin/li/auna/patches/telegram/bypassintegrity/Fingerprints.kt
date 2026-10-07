package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.bypassIntegrityMethod by gettingFirstMethodDeclaratively(
    "basicIntegrity", "ctsProfileMatch",
)

internal val BytecodePatchContext.spoofSignatureMethod by gettingFirstMethodDeclaratively {
    name("getCertificateSHA256Fingerprint")
    definingClass("AndroidUtilities;")
}
