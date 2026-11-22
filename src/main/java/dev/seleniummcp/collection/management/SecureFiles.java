package dev.seleniummcp.collection.management;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

final class SecureFiles {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private final Windows windows;
    private final int uid;

    SecureFiles() throws IOException {
        windows = com.sun.jna.Platform.isWindows() ? new Windows() : null;
        if (windows == null && !com.sun.jna.Platform.isLinux()) throw unsafe();
        uid = windows == null ? Native.load("c", Unix.class).geteuid() : -1;
    }

    public interface Unix extends Library {
        int geteuid();
    }

    public interface Security extends StdCallLibrary {
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(com.sun.jna.WString sddl,
                int revision, PointerByReference descriptor, IntByReference length);
    }

    static final class Guard implements AutoCloseable {
        private final List<WinNT.HANDLE> handles = new ArrayList<>();

        @Override public void close() {
            for (var handle : handles.reversed()) Kernel32.INSTANCE.CloseHandle(handle);
            handles.clear();
        }
    }

    Guard guard(Path directory, boolean privateDirectory) throws IOException {
        var guard = new Guard();
        try {
            Path path = directory.getRoot();
            if (path == null) throw unsafe();
            if (windows != null && (!path.toString().matches("[A-Za-z]:\\\\")
                    || Kernel32.INSTANCE.GetDriveType(path.toString()) != 3)) throw unsafe();
            check(path, true, privateDirectory && path.equals(directory), guard);
            for (Path name : directory) {
                path = path.resolve(name);
                check(path, true, privateDirectory && path.equals(directory), guard);
            }
            String filesystem = Files.getFileStore(directory).type().toLowerCase(java.util.Locale.ROOT);
            if (!(windows != null ? java.util.Set.of("ntfs", "refs") :
                    java.util.Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "tmpfs", "overlay")).contains(filesystem)) {
                throw unsafe();
            }
            return guard;
        } catch (IOException | RuntimeException | LinkageError failure) {
            guard.close();
            throw failure;
        }
    }

    void checkFile(Path file) throws IOException {
        try (var guard = new Guard()) {
            check(file, false, true, guard);
        }
    }

    void createPrivateFile(Path path) throws IOException {
        if (windows == null) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            windows.create(path);
        }
    }

    private void check(Path path, boolean directory, boolean privateEntry, Guard guard) throws IOException {
        if (windows != null) {
            windows.check(path, directory, privateEntry, guard);
            return;
        }
        var attrs = Files.readAttributes(path, "unix:uid,mode,nlink,isDirectory,isRegularFile", NOFOLLOW);
        int owner = (Integer) attrs.get("uid");
        int mode = (Integer) attrs.get("mode");
        if (!(Boolean) attrs.get(directory ? "isDirectory" : "isRegularFile")
                || (privateEntry ? owner != uid : owner != uid && owner != 0)
                || (privateEntry ? (mode & 0077) != 0 : (mode & 0022) != 0 && (owner != 0 || (mode & 01000) == 0))
                || (!directory && ((Number) attrs.get("nlink")).longValue() != 1)) throw unsafe();
    }

    private static IOException unsafe() { return new IOException("Cannot verify private storage."); }

    private static final class Windows {
        private static final int OPEN_REPARSE_POINT = 0x00200000;
        private static final int PROTECTED_DACL = 0x1000;
        private static final String SYSTEM = "S-1-5-18";
        private static final String ADMINS = "S-1-5-32-544";
        private static final String INSTALLER = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
        // Include explicit and generic namespace/metadata mutation rights. Creating a new sibling is harmless.
        private static final int ANCESTOR_MUTATION = 0x500d0150;
        private final String sid;

        Windows() throws IOException {
            var token = new WinNT.HANDLEByReference();
            if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) throw unsafe();
            try {
                sid = Advapi32Util.getTokenAccount(token.getValue()).sidString;
            } finally {
                Kernel32.INSTANCE.CloseHandle(token.getValue());
            }
            if (sid == null || !sid.matches("S-[0-9-]+")) throw unsafe();
        }

        void check(Path path, boolean directory, boolean privateEntry, Guard guard) throws IOException {
            var handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.READ_CONTROL | WinNT.FILE_READ_ATTRIBUTES,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING,
                    OPEN_REPARSE_POINT | WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw unsafe();
            guard.handles.add(handle);
            try (var tag = new Memory(8); var standard = new Memory(24)) {
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 9, tag, new DWORD(8))) throw unsafe();
                int flags = tag.getInt(0);
                if ((flags & 0x400) != 0 || ((flags & 0x10) != 0) != directory) throw unsafe();
                if (!directory && (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 1, standard, new DWORD(24))
                        || standard.getInt(16) != 1)) throw unsafe();
            }
            var pointer = new PointerByReference();
            if (Advapi32.INSTANCE.GetSecurityInfo(handle, 1, 5, null, null, null, null, pointer) != 0
                    || pointer.getValue() == null) throw unsafe();
            try {
                var sd = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(pointer.getValue());
                var acl = sd.getDiscretionaryACL();
                String owner = Advapi32Util.convertSidToStringSid(sd.getOwner());
                if (acl == null || (privateEntry ? !sid.equals(owner) || (sd.Control & PROTECTED_DACL) == 0
                        : !trusted(owner, false))) throw unsafe();
                for (var ace : acl.getACEs()) {
                    if (!privateEntry && (ace.AceFlags & 0x08) != 0) continue; // inherit-only
                    if (ace instanceof WinNT.ACCESS_DENIED_ACE) continue;
                    if (!(ace instanceof WinNT.ACCESS_ALLOWED_ACE allow)
                            || (!trusted(allow.getSidString(), privateEntry)
                            && (privateEntry || (allow.Mask & ANCESTOR_MUTATION) != 0))) throw unsafe();
                }
            } finally {
                Kernel32.INSTANCE.LocalFree(pointer.getValue());
            }
        }

        private boolean trusted(String candidate, boolean privateEntry) {
            return sid.equals(candidate) || SYSTEM.equals(candidate) || ADMINS.equals(candidate)
                    || (!privateEntry && INSTALLER.equals(candidate));
        }

        void create(Path path) throws IOException {
            var descriptor = new PointerByReference();
            var security = Native.load("Advapi32", Security.class);
            String sddl = "O:" + sid + "D:P(A;;FA;;;" + sid + ")(A;;FA;;;SY)(A;;FA;;;BA)";
            if (!security.ConvertStringSecurityDescriptorToSecurityDescriptorW(new com.sun.jna.WString(sddl),
                    1, descriptor, null)) throw unsafe();
            try {
                var attributes = new WinBase.SECURITY_ATTRIBUTES();
                attributes.lpSecurityDescriptor = descriptor.getValue();
                var handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.READ_CONTROL,
                        WinNT.FILE_SHARE_READ, attributes, WinNT.CREATE_NEW, OPEN_REPARSE_POINT, null);
                if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                    int error = Kernel32.INSTANCE.GetLastError();
                    if (error == 80 || error == 183) throw new FileAlreadyExistsException(path.getFileName().toString());
                    throw unsafe();
                }
                Kernel32.INSTANCE.CloseHandle(handle);
            } finally {
                Kernel32.INSTANCE.LocalFree(descriptor.getValue());
            }
        }
    }
}
