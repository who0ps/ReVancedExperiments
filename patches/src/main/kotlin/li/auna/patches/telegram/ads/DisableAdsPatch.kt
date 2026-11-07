package li.auna.patches.telegram.ads

import app.revanced.patcher.extensions.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import li.auna.util.returnEarly

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide sponsored ads",
    description = "Hides sponsored ads in channels",
) {
    compatibleWith(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "uz.unnarsx.cherrygram"
    )

    apply {
        val candidates = findGetSponsoredMessagesMethod()
        if (candidates.size != 1) {
            throw PatchException(
                "Hide sponsored ads: expected one messages.getSponsoredMessages method, found ${candidates.size}"
            )
        }
        firstMethod(candidates.single()).returnEarly()
    }
}
