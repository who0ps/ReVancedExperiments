package li.auna.patches.telegram.downloadboost

import app.revanced.patcher.*
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

@Suppress("unused")
val downloadBoostPatch = bytecodePatch(
    name = "Download Speed Boost",
    description = "Boosts download speed",
) {
    compatibleWith(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "uz.unnarsx.cherrygram"
    )

    apply {
        val originalMethod = updateParamsMethod
        val className = originalMethod.definingClass
        val classDef = firstClassDef(className)

        classDef.methods.removeIf { it.name == originalMethod.name }
        classDef.methods.add(
            ImmutableMethod(
                className,
                originalMethod.name,
                emptyList(),
                originalMethod.returnType,
                originalMethod.accessFlags,
                null,
                null,
                MutableMethodImplementation(5)
            ).toMutable().apply {
                addInstructions(
                    """
                        # Use 512 KiB chunks and eight concurrent requests for regular files.
                        const/high16 v0, 0x80000
                        iput v0, p0, Lorg/telegram/messenger/g7;->n:I

                        const/16 v0, 0x8
                        iput v0, p0, Lorg/telegram/messenger/g7;->o:I
                        iput v0, p0, Lorg/telegram/messenger/g7;->p:I

                        # Keep Telegram's smaller chunk size for stories.
                        iget-boolean v0, p0, Lorg/telegram/messenger/g7;->j:Z
                        if-eqz v0, :cond_story_end

                        const/high16 v0, 0x20000
                        iput v0, p0, Lorg/telegram/messenger/g7;->n:I

                        const/16 v0, 0x8
                        iput v0, p0, Lorg/telegram/messenger/g7;->p:I

                        :cond_story_end
                        # Recompute the maximum CDN parts using the selected chunk size.
                        iget v0, p0, Lorg/telegram/messenger/g7;->n:I
                        const-wide/32 v2, 0x7d000000
                        int-to-long v0, v0
                        div-long/2addr v2, v0
                        long-to-int v0, v2
                        iput v0, p0, Lorg/telegram/messenger/g7;->q:I

                        return-void
                    """
                )
            }
        )
    }
}
