package li.auna.patches.telegram.disableautoupdate

import app.revanced.patcher.patch.bytecodePatch
import li.auna.util.returnEarly

@Suppress("unused")
val unlockProPatch = bytecodePatch(
    name = "Disable Auto Update",
    description = "Disable Auto Update",
) {
    compatibleWith(
        "org.telegram.messenger", "org.telegram.messenger.web", "uz.unnarsx.cherrygram"
    )

    apply {
        // Never report a new version as available, so no update badge/popup is created.
        setNewAppVersionAvailableMethod.returnEarly(false)
        // Never show the blocking "update required" screen.
        showBlockingUpdateMethod.returnEarly()
    }
}
