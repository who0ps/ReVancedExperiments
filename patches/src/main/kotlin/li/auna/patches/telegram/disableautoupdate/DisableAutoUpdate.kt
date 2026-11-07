package li.auna.patches.telegram.disableautoupdate

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val unlockProPatch = bytecodePatch(
    name = "Disable Auto Update",
    description = "Disable Auto Update",
) {
    compatibleWith(
        "org.telegram.messenger", "org.telegram.messenger.web", "uz.unnarsx.cherrygram"
    )

    apply {
        val method = launchActivityOnResumeMethod
        val updateField = "Lorg/telegram/messenger/tz;->j1:Lorg/telegram/tgnet/TLRPC\$TL_help_appUpdate;"

        val updateReadIndex = method.implementation!!.instructions.indexOfFirst { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            field?.definingClass == "Lorg/telegram/messenger/tz;" &&
                field.name == "j1" &&
                field.type == "Lorg/telegram/tgnet/TLRPC\$TL_help_appUpdate;"
        }
        if (updateReadIndex < 0) {
            throw PatchException("Disable Auto Update: pending app update read not found in LaunchActivity.onResume()")
        }

        val updateRegister = method.getInstruction<OneRegisterInstruction>(updateReadIndex).registerA
        method.replaceInstruction(updateReadIndex, "const/4 v$updateRegister, 0x0")
        method.addInstructions(
            updateReadIndex + 1,
            "sput-object v$updateRegister, $updateField"
        )
    }
}
