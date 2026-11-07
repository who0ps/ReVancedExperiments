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

        // Resolve the catch-all handler from the DEX try table. The handler's
        // first object move writes the fallback value into the same local that
        // receives Utilities.f([B)'s result on the normal path. This is more
        // reliable than assuming labels, moves, and gotos occupy fixed indices.
        val instructionAddresses = IntArray(instructions.size)
        var codeAddress = 0
        instructions.forEachIndexed { index, instruction ->
            instructionAddresses[index] = codeAddress
            codeAddress += instruction.codeUnits
        }

        val bytesToHexCodeAddress = instructionAddresses[bytesToHexCallIndex]
        val matchingTryBlocks = methodImplementation.tryBlocks.filter { tryBlock ->
            bytesToHexCodeAddress >= tryBlock.startCodeAddress &&
                bytesToHexCodeAddress < tryBlock.startCodeAddress + tryBlock.codeUnitCount &&
                tryBlock.exceptionHandlers.any { it.exceptionType == null }
        }
        val catchHandlerAddresses = matchingTryBlocks
            .flatMap { tryBlock ->
                tryBlock.exceptionHandlers
                    .filter { it.exceptionType == null }
                    .map { it.handlerCodeAddress }
            }
            .distinct()

        if (catchHandlerAddresses.size != 1) {
            throw PatchException(
                "Bypass Integrity: expected one catch-all handler for certificate call, found ${catchHandlerAddresses.size}"
            )
        }

        val catchHandlerAddress = catchHandlerAddresses.single()
        val catchHandlerIndex = instructionAddresses.indexOf(catchHandlerAddress)
        if (catchHandlerIndex < 0 || catchHandlerIndex >= timeZoneIndex) {
            throw PatchException("Bypass Integrity: certificate catch-all handler location not found")
        }

        val fingerprintRegister = (catchHandlerIndex until minOf(catchHandlerIndex + 4, timeZoneIndex))
            .firstNotNullOfOrNull { index ->
                val instruction = instructions[index] as? TwoRegisterInstruction
                    ?: return@firstNotNullOfOrNull null
                if (instruction.opcode.name.startsWith("MOVE_OBJECT")) {
                    instruction.registerA
                } else {
                    null
                }
            } ?: throw PatchException("Bypass Integrity: certificate fallback register not found in catch handler")

        // Confirm that the normal path writes to the same local within the
        // protected region containing the bytesToHex call.
        val tryEndCodeAddress = matchingTryBlocks
            .filter { tryBlock ->
                tryBlock.exceptionHandlers.any {
                    it.exceptionType == null && it.handlerCodeAddress == catchHandlerAddress
                }
            }
            .maxOf { it.startCodeAddress + it.codeUnitCount }

        val hasNormalPathAssignment = instructions.indices.any { index ->
            val address = instructionAddresses[index]
            val instruction = instructions[index] as? TwoRegisterInstruction
            address > bytesToHexCodeAddress &&
                address < tryEndCodeAddress &&
                index < timeZoneIndex &&
                instruction != null &&
                instruction.opcode.name.startsWith("MOVE_OBJECT") &&
                instruction.registerA == fingerprintRegister
        }
        if (!hasNormalPathAssignment) {
            throw PatchException(
                "Bypass Integrity: catch handler register v$fingerprintRegister has no normal-path assignment"
            )
        }

        constructor.addInstructions(
            timeZoneIndex,
            """const-string v$fingerprintRegister, "$TELEGRAM_SIGNATURE_SHA256""""
        )
    }
}
