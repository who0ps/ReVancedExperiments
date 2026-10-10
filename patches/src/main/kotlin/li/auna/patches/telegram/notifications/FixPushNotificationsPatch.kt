// SPDX-FileCopyrightText: 2026 OpenAI
// SPDX-License-Identifier: AGPL-3.0-or-later
// Port based on reseam/patches FixPushNotificationsPatch.kt and SpoofSignaturePatch.kt.

package li.auna.patches.telegram.notifications

import app.revanced.patcher.*
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

private const val PATCH_NAME = "Fix push notifications"
private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
private const val EXTENSION_CLASS = "Lapp/revanced/extension/telegram/notifications/OriginalSignature;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
private const val SIGNING_INFO = "Landroid/content/pm/SigningInfo;"
private const val SIGNATURES = "[Landroid/content/pm/Signature;"
private const val STRING = "Ljava/lang/String;"
private const val BYTE_ARRAY = "[B"
private const val BOOLEAN = "Z"
private const val FLAGS = "Landroid/content/pm/PackageManager\$PackageInfoFlags;"

private data class Redirect(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val replacement: String,
) {
    fun matches(reference: MethodReference): Boolean =
        reference.definingClass == owner && reference.name == name &&
            reference.parameterTypes.map { it.toString() } == parameters && reference.returnType == returnType
}

private val redirects = listOf(
    Redirect(
        PACKAGE_MANAGER, "getPackageInfo", listOf(STRING, "I"), PACKAGE_INFO,
        EXTENSION_CLASS + "->getPackageInfo(" + PACKAGE_MANAGER + STRING + "I)" + PACKAGE_INFO,
    ),
    Redirect(
        PACKAGE_MANAGER, "getPackageInfo", listOf(STRING, FLAGS), PACKAGE_INFO,
        EXTENSION_CLASS + "->getPackageInfo(" + PACKAGE_MANAGER + STRING + FLAGS + ")" + PACKAGE_INFO,
    ),
    Redirect(
        PACKAGE_MANAGER, "hasSigningCertificate", listOf(STRING, BYTE_ARRAY, "I"), BOOLEAN,
        EXTENSION_CLASS + "->hasSigningCertificate(" + PACKAGE_MANAGER + STRING + BYTE_ARRAY + "I)Z",
    ),
    Redirect(
        SIGNING_INFO, "getApkContentsSigners", emptyList(), SIGNATURES,
        EXTENSION_CLASS + "->getApkContentsSigners(" + SIGNING_INFO + ")" + SIGNATURES,
    ),
    Redirect(
        SIGNING_INFO, "getSigningCertificateHistory", emptyList(), SIGNATURES,
        EXTENSION_CLASS + "->getSigningCertificateHistory(" + SIGNING_INFO + ")" + SIGNATURES,
    ),
    Redirect(
        SIGNING_INFO, "hasMultipleSigners", emptyList(), BOOLEAN,
        EXTENSION_CLASS + "->hasMultipleSigners(" + SIGNING_INFO + ")Z",
    ),
)

private val supportedInvokeOpcodes = setOf(
    "INVOKE_VIRTUAL", "INVOKE_VIRTUAL_RANGE", "INVOKE_INTERFACE", "INVOKE_INTERFACE_RANGE",
)

@Suppress("unused")
val fixPushNotificationsPatch = bytecodePatch(
    name = PATCH_NAME,
    description = "Shows Firebase the original Telegram signing certificate for local package-signature checks.",
) {
    compatibleWith("org.telegram.messenger")
    extendWith("extensions/telegram-push.rve")

    apply {
        val apk = locateInputApk(this)
        val certificates = try {
            ApkSignerReader.readSignerCertificates(apk)
        } catch (e: Exception) {
            throw PatchException(PATCH_NAME + ": could not read the input APK signing certificate: " + e.message)
        }
        if (certificates.isEmpty()) throw PatchException(PATCH_NAME + ": the input APK has no signing certificates")
        val encodedCertificates = certificates.joinToString(",") { it.toLowerHex() }

        // Preflight call sites before mutating classes. Fail closed if the APK layout has changed.
        val candidateClasses = classDefs.filter { classDef ->
            !classDef.type.startsWith("Lapp/revanced/extension/") &&
                classDef.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode.name in supportedInvokeOpcodes &&
                            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                                redirects.any { it.matches(ref) }
                            } == true
                    } == true
                }
        }
        val preflightCounts = redirects.associateWith { 0 }.toMutableMap()
        for (classDef in candidateClasses) {
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    if (instruction.opcode.name !in supportedInvokeOpcodes) continue
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    val redirect = redirects.firstOrNull { it.matches(reference) } ?: continue
                    val registerCount = invokeRegisters(instruction).size
                    val expectedCount = reference.parameterTypes.sumOf { type ->
                        if (type.toString() == "J" || type.toString() == "D") 2 else 1
                    } + 1
                    if (registerCount != expectedCount) {
                        throw PatchException(PATCH_NAME + ": unexpected register count for " +
                            reference.definingClass + "->" + reference.name)
                    }
                    preflightCounts[redirect] = preflightCounts.getValue(redirect) + 1
                }
            }
        }
        val getPackageInfoCount = preflightCounts.filterKeys { it.name == "getPackageInfo" }.values.sum()
        if (getPackageInfoCount == 0) {
            throw PatchException(PATCH_NAME +
                ": no PackageManager.getPackageInfo call sites were found; no patch was applied")
        }

        val extensionDef = classDefs.firstOrNull { it.type == EXTENSION_CLASS }
            ?: throw PatchException(PATCH_NAME + ": extension class " + EXTENSION_CLASS + " was not merged")
        val mutableExtension = classDefs.getOrReplaceMutable(extensionDef)
        val ownPackage = mutableExtension.methods.singleOrNull {
            it.name == "ownPackage" && it.returnType == STRING && it.parameterTypes.isEmpty()
        } ?: throw PatchException(PATCH_NAME + ": extension method ownPackage() is missing or ambiguous")
        val certificateMethod = mutableExtension.methods.singleOrNull {
            it.name == "certificates" && it.returnType == STRING && it.parameterTypes.isEmpty()
        } ?: throw PatchException(PATCH_NAME + ": extension method certificates() is missing or ambiguous")
        installStringReturn(ownPackage, TELEGRAM_PACKAGE, "ownPackage")
        installStringReturn(certificateMethod, encodedCertificates, "certificates")

        val callCounts = redirects.associateWith { 0 }.toMutableMap()
        for (classDef in candidateClasses) {
            val mutableClass = classDefs.getOrReplaceMutable(classDef)
            for (method in mutableClass.methods.toList()) {
                val implementation = method.implementation ?: continue
                for (index in implementation.instructions.indices.reversed()) {
                    val instruction = implementation.instructions[index]
                    if (instruction.opcode.name !in supportedInvokeOpcodes) continue
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    val redirect = redirects.firstOrNull { it.matches(reference) } ?: continue
                    val registers = invokeRegisters(instruction)
                    val expectedCount = reference.parameterTypes.sumOf { type ->
                        if (type.toString() == "J" || type.toString() == "D") 2 else 1
                    } + 1
                    if (registers.size != expectedCount) {
                        throw PatchException(PATCH_NAME + ": unexpected register count for " +
                            reference.definingClass + "->" + reference.name)
                    }
                    val isRange = instruction.opcode.name.endsWith("_RANGE")
                    val registerList = if (isRange) {
                        "v" + registers.first() + " .. v" + registers.last()
                    } else {
                        registers.joinToString(", ") { "v" + it }
                    }
                    val opcode = if (isRange) "invoke-static/range" else "invoke-static"
                    method.replaceInstruction(index, opcode + " { " + registerList + " }, " + redirect.replacement)
                    callCounts[redirect] = callCounts.getValue(redirect) + 1
                }
            }
        }

        val totalRedirects = callCounts.values.sum()
        if (callCounts.filterKeys { it.name == "getPackageInfo" }.values.sum() == 0 || totalRedirects == 0) {
            throw PatchException(PATCH_NAME +
                ": no PackageManager.getPackageInfo call sites were patched; refusing to continue")
        }

        println(PATCH_NAME + ": embedded " + certificates.size + " original signer certificate(s); redirected " +
            totalRedirects + " call site(s): " +
            callCounts.filterValues { it > 0 }.entries.joinToString { it.key.name + "=" + it.value })
    }
}

private fun installStringReturn(method: Method, value: String, label: String) {
    val implementation = method.implementation
        ?: throw PatchException(PATCH_NAME + ": extension method " + label + "() has no implementation")
    val instructions = implementation.instructions.toList()
    if (implementation.registerCount < 1 || instructions.size != 2 ||
        instructions[0].opcode.name != "CONST_4" || instructions[1].opcode.name != "RETURN_OBJECT") {
        throw PatchException(PATCH_NAME + ": extension method " + label + "() no longer has the expected fallback")
    }
    val mutableMethod = method as? app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
        ?: throw PatchException(PATCH_NAME + ": extension method " + label + "() is not mutable")
    mutableMethod.replaceInstruction(0, "const-string v0, \"" + value + "\"")
}

private fun invokeRegisters(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): List<Int> =
    when (instruction) {
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount)
        is RegisterRangeInstruction ->
            (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> throw PatchException(PATCH_NAME + ": unsupported invoke instruction format " + instruction.opcode.format)
    }

/**
 * Patcher 22.x does not provide a stable public APK-file accessor across all builds.
 * This adapter searches a narrow set of Patcher configuration fields and fails closed.
 */
private fun locateInputApk(context: BytecodePatchContext): File {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun search(value: Any?, depth: Int): File? {
        if (value == null || depth > 5 || !visited.add(value)) return null
        if (value is File) return value.takeIf { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        val type = value.javaClass
        if (!type.name.startsWith("app.revanced.patcher.")) return null
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val inspect = field.name in setOf("apkFile", "inputApkFile", "config", "patcherConfig", "apkInfo") ||
                    field.type.name.startsWith("app.revanced.patcher.")
                if (!inspect) continue
                val child = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue
                if (field.name in setOf("apkFile", "inputApkFile") && child is File &&
                    child.isFile && child.extension.equals("apk", ignoreCase = true)) return child
                search(child, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    return search(context, 0) ?: throw PatchException(PATCH_NAME +
        ": cannot locate the original APK file in this ReVanced Patcher build; refusing to spoof an unknown signing identity")
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    val digits = "0123456789abcdef"
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(digits[value ushr 4])
        append(digits[value and 0x0f])
    }
}
