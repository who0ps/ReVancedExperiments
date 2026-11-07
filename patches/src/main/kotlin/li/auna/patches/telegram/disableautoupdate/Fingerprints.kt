package li.auna.patches.telegram.disableautoupdate

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/** The update prompt is inlined into LaunchActivity.onResume() in Telegram 13.2.1. */
internal val BytecodePatchContext.launchActivityOnResumeMethod by gettingFirstMethodDeclaratively {
    name("onResume")
    definingClass("Lorg/telegram/ui/LaunchActivity;")
    returnType("V")
    parameterTypes()
}
