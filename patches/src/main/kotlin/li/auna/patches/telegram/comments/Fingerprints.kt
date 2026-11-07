package li.auna.patches.telegram.comments

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Telegram 13.2.1 R8-obfuscates MessageObject.isLinkedToChat(long).
 * Identify it by the Message.replies -> MessageReplies.channel_id data flow
 * instead of source-level class and method names.
 */
internal fun BytecodePatchContext.findIsLinkedToChatMethod() =
    classDefs.flatMap { classDef ->
        classDef.methods.filter { method ->
            val instructions = method.implementation?.instructions
                ?: return@filter false

            method.definingClass.startsWith("Lorg/telegram/messenger/") &&
                method.parameterTypes.map { it.toString() } == listOf("J") &&
                method.returnType == "Z" &&
                instructions.any { instruction ->
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    field?.definingClass == "Lorg/telegram/tgnet/TLRPC\$Message;" &&
                        field.name == "replies" &&
                        field.type == "Lorg/telegram/tgnet/TLRPC\$MessageReplies;"
                } &&
                instructions.any { instruction ->
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    field?.definingClass == "Lorg/telegram/tgnet/TLRPC\$MessageReplies;" &&
                        field.name == "channel_id" &&
                        field.type == "J"
                } &&
                instructions.any { instruction ->
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    field?.definingClass == method.definingClass &&
                        field.type == "Lorg/telegram/tgnet/TLRPC\$Message;"
                }
        }
    }
