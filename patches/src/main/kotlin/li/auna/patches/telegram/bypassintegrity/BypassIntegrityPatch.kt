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
        // inlined into ConnectionsManager.<init>(int). Its bytesToHex(byte[]) result
        // is copied into a shared local on the normal path; the catch path writes
        // the empty-string fallback into the same local before TimeZone is read.
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
        val resultInstruction = instructions.getOrNull(bytesToHexCallIndex + 1)
        if (
            resultInstruction !is OneRegisterInstruction ||
            resultInstruction.opcode.name != "MOVE_RESULT_OBJECT"
        ) {
            throw PatchException("Bypass Integrity: certificate hex result register not found")
        }
        val hexResultRegister = resultInstruction.registerA

        // Follow the normal-path MOVE_OBJECT from the bytesToHex result to the
        // register shared with the catch handler. Do not assume it is adjacent
        // to TimeZone.getDefault(): try/catch labels and a goto sit in between.
        val fingerprintRegister = (bytesToHexCallIndex + 2 until timeZoneIndex)
            .firstNotNullOfOrNull { index ->
                val instruction = instructions[index]
                if (
                    instruction is TwoRegisterInstruction &&
                    instruction.opcode.name.startsWith("MOVE_OBJECT") &&
                    instruction.registerB == hexResultRegister
                ) {
                    instruction.registerA
                } else {
                    null
                }
            } ?: throw PatchException("Bypass Integrity: certificate fingerprint merge register not found")

        constructor.addInstructions(
            timeZoneIndex,
            """const-string v$fingerprintRegister, "$TELEGRAM_SIGNATURE_SHA256""""
        )
    }
}
