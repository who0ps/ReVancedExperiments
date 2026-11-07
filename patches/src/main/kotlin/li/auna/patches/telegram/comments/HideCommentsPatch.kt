package li.auna.patches.telegram.comments

import app.revanced.patcher.patch.PatchException
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
        val candidates = findIsLinkedToChatMethod()
        if (candidates.size != 1) {
            throw PatchException(
                "Hide comments button: expected one MessageObject.isLinkedToChat(long) method, found ${candidates.size}"
            )
        }

        val candidate = candidates.single()
        val mutableMethod = firstClassDef(candidate.definingClass).methods.single { method ->
            method.name == candidate.name &&
                method.returnType == candidate.returnType &&
                method.parameterTypes.map { it.toString() } == candidate.parameterTypes.map { it.toString() }
        }
        mutableMethod.returnEarly(false)
    }
}
