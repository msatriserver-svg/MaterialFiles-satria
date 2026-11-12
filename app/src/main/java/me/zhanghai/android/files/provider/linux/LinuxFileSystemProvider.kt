/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import java8.nio.file.attribute.FileAttributeView
import me.zhanghai.android.files.provider.root.RootableFileSystemProvider

object LinuxFileSystemProvider : RootableFileSystemProvider(
    { LocalLinuxFileSystemProvider(it as LinuxFileSystemProvider) }
) {
    override val localProvider: LocalLinuxFileSystemProvider
        get() = super.localProvider as LocalLinuxFileSystemProvider

    internal val fileSystem: LinuxFileSystem
        get() = localProvider.fileSystem

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        LocalLinuxFileSystemProvider.supportsFileAttributeView(type)
}
