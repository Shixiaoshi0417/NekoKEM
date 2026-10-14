package com.shixiaoshi0417.nekokem.files

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric 4.14's Linux shadow returns st_uid/st_nlink = 0 and omits chmod.
 * Use real host POSIX metadata for the SAF checks instead of disabling them.
 * These tests require a Linux host; provider streams remain fault-injectable.
 */
@Implements(Os::class)
class HostPosixShadow {
    companion object {
        @JvmStatic @Implementation
        fun getuid(): Int = Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Int

        @JvmStatic @Implementation
        fun chmod(path: String, mode: Int) {
            try {
                Files.setAttribute(Paths.get(path), "unix:mode", mode)
            } catch (error: IOException) {
                throw ErrnoException("chmod", OsConstants.EIO, error)
            }
        }

        @JvmStatic @Implementation
        fun rename(oldPath: String, newPath: String) {
            try {
                Files.move(
                    Paths.get(oldPath), Paths.get(newPath),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (error: IOException) {
                throw ErrnoException("rename", OsConstants.EIO, error)
            }
        }

        @JvmStatic @Implementation
        fun lstat(path: String): StructStat {
            try {
                val values = Files.readAttributes(
                    Paths.get(path), "unix:*", LinkOption.NOFOLLOW_LINKS,
                )
                fun number(key: String) = (values.getValue(key) as Number).toLong()
                fun seconds(key: String) = (values.getValue(key) as FileTime).toMillis() / 1000
                return StructStat(
                    number("dev"), number("ino"), number("mode").toInt(), number("nlink"),
                    number("uid").toInt(), number("gid").toInt(), number("rdev"), number("size"),
                    seconds("lastAccessTime"), seconds("lastModifiedTime"), seconds("ctime"),
                    4096, 0,
                )
            } catch (error: IOException) {
                throw ErrnoException("lstat", OsConstants.EIO, error)
            }
        }
    }
}
