package li.auna.patches.telegram.downloadboost

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** FileLoadOperation.updateParams() is R8-renamed to g7.t() in Telegram 13.2.1. */
internal val BytecodePatchContext.updateParamsMethod by gettingFirstMethodDeclaratively {
    name("t")
    definingClass("Lorg/telegram/messenger/g7;")
    returnType("V")
}
