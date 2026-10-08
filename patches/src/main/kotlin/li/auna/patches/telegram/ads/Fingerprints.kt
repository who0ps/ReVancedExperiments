package li.auna.patches.telegram.ads

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** MessagesController.getSponsoredMessages(long): returns null when there are no ads. */
internal val BytecodePatchContext.getSponsoredMessagesMethod by gettingFirstMethodDeclaratively {
    name("getSponsoredMessages")
    definingClass("Lorg/telegram/messenger/MessagesController;")
    returnType("Lorg/telegram/messenger/MessagesController\$SponsoredMessagesInfo;")
    parameterTypes("J")
}
