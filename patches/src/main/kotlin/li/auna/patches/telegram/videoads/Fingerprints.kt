package li.auna.patches.telegram.videoads

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

/**
 * org.telegram.messenger.video.VideoAds.load()
 *
 * Private method that sends messages.getSponsoredMessages for the full-screen video viewer.
 * Neither the class nor the method name is obfuscated in the analysed build.
 */
internal val BytecodePatchContext.loadVideoAdsMethod by gettingFirstMethodDeclaratively {
    name("load")
    definingClass("VideoAds;")
    returnType("V")
}
