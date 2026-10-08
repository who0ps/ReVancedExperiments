package li.auna.patches.telegram.comments

import app.revanced.patcher.patch.bytecodePatch
import li.auna.util.returnEarly

@Suppress("unused")
val hideCommentsPatch = bytecodePatch(
    name = "Hide comments button",
    description = "Hides the comments button under channel posts",
    use = false,
) {
    compatibleWith(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "uz.unnarsx.cherrygram"
    )

    apply {
        isLinkedToChatMethod.returnEarly(false)
    }
}
