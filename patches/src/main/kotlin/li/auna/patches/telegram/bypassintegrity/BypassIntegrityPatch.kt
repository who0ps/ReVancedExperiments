package li.auna.patches.telegram.bypassintegrity

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.string
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import li.auna.util.indexOfFirstInstruction
import li.auna.util.isBranchInstruction
import li.auna.util.writeRegister

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
        val methodImplementation = constructor.implementation
            ?: throw PatchException("Bypass Integrity: ConnectionsManager constructor has no implementation")
        val instructions = methodImplementation.instructions.toList()

        val timeZoneIndex = instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == "Ljava/util/TimeZone;" &&
                reference.name == "getDefault" &&
                reference.parameterTypes.isEmpty()
        }
        if (timeZoneIndex <= 0) {
            throw PatchException("Bypass Integrity: certificate fingerprint merge point not found")
        }

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

        // The bytesToHex() call result (a MOVE_RESULT_OBJECT) is the start of the
        // normal path. It may then be renamed into a shared local before the
        // normal path's unconditional branch jumps past the exception's
        // catch-all fallback assignment, converging with it at timeZoneIndex.
        // Walk forward from the call, tracking the last register written, and
        // stop at that branch. This only depends on the normal path's shape,
        // not on the exact instruction the catch-all handler uses to write its
        // fallback value (which has differed across Telegram builds), since
        // both paths must write the same local for the merge to be valid.
        var fingerprintRegister: Int? = null
        var scanIndex = bytesToHexCallIndex + 1
        while (scanIndex < timeZoneIndex) {
            val instruction = instructions[scanIndex]
            if (instruction.isBranchInstruction) break
            instruction.writeRegister?.let { fingerprintRegister = it }
            scanIndex++
        }
        val register = fingerprintRegister
            ?: throw PatchException("Bypass Integrity: certificate fingerprint register not found after bytesToHex call")

        constructor.addInstructions(
            timeZoneIndex,
            """const-string v$register, "$TELEGRAM_SIGNATURE_SHA256""""
        )
    }
}
