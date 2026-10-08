package li.auna.patches.telegram.comments

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** MessageObject.isLinkedToChat(long): true when a channel post has a linked discussion chat. */
internal val BytecodePatchContext.isLinkedToChatMethod by gettingFirstMethodDeclaratively {
    name("isLinkedToChat")
    definingClass("Lorg/telegram/messenger/MessageObject;")
    returnType("Z")
    parameterTypes("J")
}
