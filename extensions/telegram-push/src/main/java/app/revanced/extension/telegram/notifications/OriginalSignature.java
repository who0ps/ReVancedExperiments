// SPDX-FileCopyrightText: 2026 OpenAI
// SPDX-License-Identifier: AGPL-3.0-or-later
package app.revanced.extension.telegram.notifications;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Spoofs local signature queries for Telegram only; does not change the installed APK signature. */
public final class OriginalSignature {
    private static final Set<SigningInfo> OWN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<SigningInfo, Boolean>())
    );
    private static volatile Signature[] signatures;

    private OriginalSignature() {}

    // Replaced by FixPushNotificationsPatch during patching.
    public static String ownPackage() { return null; }
    public static String certificates() { return null; }

    public static PackageInfo getPackageInfo(PackageManager manager, String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        return spoofed(manager.getPackageInfo(packageName, flags));
    }

    public static PackageInfo getPackageInfo(
            PackageManager manager, String packageName, PackageManager.PackageInfoFlags flags)
            throws PackageManager.NameNotFoundException {
        return spoofed(manager.getPackageInfo(packageName, flags));
    }

    public static boolean hasSigningCertificate(PackageManager manager, String packageName, byte[] certificate, int type) {
        if (!ownPackage().equals(packageName)) {
            return manager.hasSigningCertificate(packageName, certificate, type);
        }
        for (Signature signature : signatures()) {
            byte[] candidate = type == PackageManager.CERT_INPUT_SHA256
                    ? sha256(signature.toByteArray()) : signature.toByteArray();
            if (Arrays.equals(candidate, certificate)) return true;
        }
        return false;
    }

    public static Signature[] getApkContentsSigners(SigningInfo info) {
        return OWN.contains(info) ? signatures().clone() : info.getApkContentsSigners();
    }

    public static Signature[] getSigningCertificateHistory(SigningInfo info) {
        return OWN.contains(info) ? signatures().clone() : info.getSigningCertificateHistory();
    }

    public static boolean hasMultipleSigners(SigningInfo info) {
        return OWN.contains(info) ? signatures().length > 1 : info.hasMultipleSigners();
    }

    private static PackageInfo spoofed(PackageInfo info) {
        if (!ownPackage().equals(info.packageName)) return info;
        if (info.signatures != null) info.signatures = signatures().clone();
        if (info.signingInfo != null) OWN.add(info.signingInfo);
        return info;
    }

    private static Signature[] signatures() {
        if (signatures == null) {
            synchronized (OriginalSignature.class) {
                if (signatures == null) {
                    String encoded = certificates();
                    if (encoded == null || encoded.isEmpty()) {
                        throw new IllegalStateException("Original Telegram signing certificate was not embedded");
                    }
                    String[] values = encoded.split(",");
                    Signature[] decoded = new Signature[values.length];
                    for (int i = 0; i < values.length; i++) decoded[i] = new Signature(values[i]);
                    signatures = decoded;
                }
            }
        }
        return signatures;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
