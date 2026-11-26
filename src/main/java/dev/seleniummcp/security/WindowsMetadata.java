package dev.seleniummcp.security;

import com.sun.jna.platform.win32.Kernel32;
import java.nio.file.Path;

/** Reject remote roots and reparse points before diagnostic metadata probes. */
public final class WindowsMetadata {
    private static final int DRIVE_FIXED = 3;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

    private WindowsMetadata() {}

    public static boolean isLocal(Path path, boolean allowMissing) {
        var kernel = Kernel32.INSTANCE;
        Path part = path.getRoot();
        if (part == null || !part.toString().matches("[A-Za-z]:\\\\")
                || kernel.GetDriveType(part.toString()) != DRIVE_FIXED) {
            return false;
        }
        for (Path name : path) {
            part = part.resolve(name);
            int attributes = kernel.GetFileAttributes(part.toString());
            if (attributes == -1) return allowMissing && kernel.GetLastError() == com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND;
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) return false;
        }
        return true;
    }
}
