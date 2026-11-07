package li.auna.patches.telegram.ads

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Telegram 13.2.1 obfuscates MessagesController.getSponsoredMessages(long).
 * Locate the method by the TL request it creates instead of its R8-renamed
 * method and return types.
 */
internal fun BytecodePatchContext.findGetSponsoredMessagesMethod() =
    classDefs.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.definingClass.startsWith("Lorg/telegram/messenger/") &&
                method.parameterTypes.map { it.toString() } == listOf("J") &&
                method.returnType.startsWith("L") &&
                method.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? TypeReference
                    reference?.type == "Lorg/telegram/tgnet/TLRPC\$TL_messages_getSponsoredMessages;"
                } == true
        }
    }
