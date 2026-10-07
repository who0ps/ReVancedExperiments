package li.auna.patches.telegram.videoads

import app.revanced.patcher.patch.bytecodePatch
import li.auna.util.returnEarly

@Suppress("unused")
val hideVideoAdsPatch = bytecodePatch(
    name = "Hide video ads",
    description = "Removes the sponsored banner shown at the bottom of the full-screen video player",
) {
    compatibleWith("org.telegram.messenger")

    apply {
        // Without the ads request nothing is loaded, so schedule() and show() are never reached.
        loadVideoAdsMethod.returnEarly()
    }
}
