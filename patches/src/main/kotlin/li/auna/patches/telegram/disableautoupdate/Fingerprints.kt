package li.auna.patches.telegram.disableautoupdate

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.setNewAppVersionAvailableMethod by gettingFirstMethodDeclaratively {
    name("setNewAppVersionAvailable")
    definingClass("Lorg/telegram/messenger/SharedConfig;")
    returnType("Z")
}

/**
 * LaunchActivity.H0(int, TLRPC.TL_help_appUpdate, boolean)
 *
 * Builds and shows the blocking "update required" screen. The method names in LaunchActivity
 * are obfuscated in newer builds (the old `checkAppUpdate` and `BlockingUpdateView` no longer
 * exist), so the method is matched by its unique parameter list instead.
 */
internal val BytecodePatchContext.showBlockingUpdateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/telegram/ui/LaunchActivity;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("I", "Lorg/telegram/tgnet/TLRPC\$TL_help_appUpdate;", "Z")
}
