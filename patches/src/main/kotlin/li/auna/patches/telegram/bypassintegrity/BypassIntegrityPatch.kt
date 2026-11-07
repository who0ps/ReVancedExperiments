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

        // In Telegram 13.2.1, AndroidUtilities.getCertificateSHA256Fingerprint() is
        // inlined into ConnectionsManager.<init>(int). The normal path copies the
        // bytesToHex result into a shared local, jumps over the catch handler, and
        // the catch path writes its fallback into that same local before TimeZone.
        val bytesToHexCallIndices = instructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (
                reference?.definingClass == "Lorg/telegram/messenger/Utilities;" &&
                reference.returnType == "Ljava/lang/String;" &&
                reference.parameterTypes.map { it.toString() } == listOf("[B")
            ) {
                index
            } else {
                null
            }
        }
        if (bytesToHexCallIndices.size != 1) {
            throw PatchException(
                "Bypass Integrity: expected one certificate bytesToHex call, found ${bytesToHexCallIndices.size}"
            )
        }

        val bytesToHexCallIndex = bytesToHexCallIndices.single()
        // In the inspected DEX, the normal path copies the hex string to a
        // shared local, jumps over the catch handler, and the catch path writes
        // its fallback to that same local. Detect this control-flow shape
        // directly; do not depend on MOVE_RESULT_OBJECT being exposed at a
        // particular instruction index by the patcher.
        val fingerprintRegisters = (bytesToHexCallIndex + 1 until timeZoneIndex - 2)
            .mapNotNull { index ->
                val normalMove = instructions[index] as? TwoRegisterInstruction
                    ?: return@mapNotNull null
                if (!normalMove.opcode.name.startsWith("MOVE_OBJECT")) {
                    return@mapNotNull null
                }

                val jump = instructions.getOrNull(index + 1)
                    ?: return@mapNotNull null
                if (!jump.opcode.name.startsWith("GOTO")) {
                    return@mapNotNull null
                }

                val fallbackMove = instructions.getOrNull(index + 2) as? TwoRegisterInstruction
                    ?: return@mapNotNull null
                if (
                    fallbackMove.opcode.name.startsWith("MOVE_OBJECT") &&
                    fallbackMove.registerA == normalMove.registerA
                ) {
                    normalMove.registerA
                } else {
                    null
                }
            }
        if (fingerprintRegisters.size != 1) {
            throw PatchException(
                "Bypass Integrity: expected one certificate try/catch merge register, found ${fingerprintRegisters.size}"
            )
        }
        val fingerprintRegister = fingerprintRegisters.single()

        constructor.addInstructions(
            timeZoneIndex,
            """const-string v$fingerprintRegister, "$TELEGRAM_SIGNATURE_SHA256""""
        )
    }
}
