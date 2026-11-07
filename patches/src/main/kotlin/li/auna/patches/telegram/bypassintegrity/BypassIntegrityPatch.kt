package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.string
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import li.auna.util.indexOfFirstInstruction

private const val TELEGRAM_SIGNATURE_SHA256 =
    "49C1522548EBACD46CE322B6FD47F6092BB745D0F88082145CAF35E14DCC38E1"

@Suppress("unused")
val bypassIntegrityPatch = bytecodePatch(
    name = "Bypass Integrity",
    description = "Bypass integrity check to allow login",
) {
    compatibleWith(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "uz.unnarsx.cherrygram"
    )

    apply {
        (bypassIntegrityMethod ?: throw PatchException("Bypass Integrity: integrity check method not found")).apply {
            val stringsToMatch = listOf("basicIntegrity", "ctsProfileMatch")

            // The result of optBoolean(...) is stored 2 instructions after each matched string.
            val indices = implementation!!.instructions.mapIndexedNotNull { index, instruction ->
                if (instruction.string in stringsToMatch) index + 2 else null
            }
            if (indices.isEmpty()) throw PatchException("Bypass Integrity: no integrity strings in method")

            indices.forEach { patchIndex ->
                val instructionRegister = getInstruction<OneRegisterInstruction>(patchIndex).registerA
                replaceInstruction(patchIndex, "const/4 v$instructionRegister, 0x1")
            }
        }

        val constructor = signatureHashConstructorMethod
        val instructions = constructor.implementation?.instructions
            ?: throw PatchException("Bypass Integrity: ConnectionsManager constructor has no implementation")

        val timeZoneIndex = instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == "Ljava/util/TimeZone;" &&
                reference.name == "getDefault" &&
                reference.parameterTypes.isEmpty()
        }
        if (timeZoneIndex <= 0) {
            throw PatchException("Bypass Integrity: certificate fingerprint merge point not found")
        }

        // The last MOVE_OBJECT before TimeZone.getDefault() receives the SHA-256
        // fingerprint on both the normal and exception paths (v13 in Telegram 13.2.1).
        val mergeInstruction = instructions[timeZoneIndex - 1]
        if (mergeInstruction !is TwoRegisterInstruction ||
            !mergeInstruction.opcode.name.startsWith("MOVE_OBJECT")
        ) {
            throw PatchException("Bypass Integrity: certificate fingerprint register not found")
        }
        val fingerprintRegister = mergeInstruction.registerA

        constructor.addInstructions(
            timeZoneIndex,
            """const-string v$fingerprintRegister, "$TELEGRAM_SIGNATURE_SHA256""""
        )
    }
}
