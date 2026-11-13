/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.closeSafe
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java8.nio.file.Path as Java8Path

object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    private val clients = mutableMapOf<Authority, SFTPClient>()
    // The SSH connection under each SFTP client, to close it together with the client.
    private val sshClients = mutableMapOf<Authority, SSHClient>()

    private val directoryFileAttributesCache =
        Collections.synchronizedMap(WeakHashMap<Path, FileAttributes>())

    @Throws(ClientException::class)
    fun access(path: Path, flags: Set<OpenMode>) {
        val file = open(path, flags, FileAttributes.EMPTY)
        try {
            file.close()
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun lstat(path: Path): FileAttributes {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                return it.also { directoryFileAttributesCache -= path }
            }
        }
        return withClient(path.authority) { it.lstat(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun mkdir(path: Path, attributes: FileAttributes) {
        val client = getClient(path.authority)
        try {
            client.sftpEngine.makeDir(path.remotePath, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(ClientException::class)
    private fun open(path: Path, flags: Set<OpenMode>, attributes: FileAttributes): RemoteFile {
        val client = getClient(path.authority)
        return try {
            client.open(path.remotePath, flags, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        flags: Set<OpenMode>,
        attributes: FileAttributes
    ): SeekableByteChannel {
        val file = open(path, flags, attributes)
        return NotifyEntryModifiedSeekableByteChannel(
            FileByteChannel(file, flags.contains(OpenMode.APPEND)), path as Java8Path
        )
    }

    @Throws(ClientException::class)
    fun readlink(path: Path): String {
        return withClient(path.authority) { it.readlink(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun realpath(path: Path): Path {
        val realPath = withClient(path.authority) { it.canonicalize(path.remotePath) }
        return path.resolve(realPath)
    }

    @Throws(ClientException::class)
    fun remove(path: Path) {
        val attributes = lstat(path)
        val isDirectory = attributes.type == FileMode.Type.DIRECTORY
        if (isDirectory) {
            rmdir(path)
        } else {
            unlink(path)
        }
    }

    // Note that unlike POSIX rename(), this won't overwrite an existing file.
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        if (newPath.authority != path.authority) {
            throw ClientException(
                SFTPException(Response.StatusCode.FAILURE, "Paths aren't on the same authority")
            )
        }
        val client = getClient(path.authority)
        try {
            client.rename(path.remotePath, newPath.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        directoryFileAttributesCache -= newPath
        LocalWatchService.onEntryDeleted(path as Java8Path)
        LocalWatchService.onEntryCreated(newPath as Java8Path)
    }

    @Throws(ClientException::class)
    fun rmdir(path: Path) {
        val client = getClient(path.authority)
        try {
            client.rmdir(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun scandir(path: Path): List<Path> {
        val files = withClient(path.authority) { it.ls(path.remotePath) }
        return files.map { file ->
            // The attributes here are from lstat().
            // https://github.com/openssh/openssh-portable/blob/71241fc05db4bbb11bb29340b44b92e2575373d8/sftp-server.c#L1110
            path.resolve(file.name).also { directoryFileAttributesCache[it] = file.attributes }
        }
    }

    @Throws(ClientException::class)
    fun setstat(path: Path, attributes: FileAttributes) {
        val client = getClient(path.authority)
        try {
            client.setattr(path.remotePath, attributes)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun stat(path: Path): FileAttributes {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                if (it.type != FileMode.Type.SYMLINK) {
                    return it.also { directoryFileAttributesCache -= path }
                }
            }
        }
        return withClient(path.authority) { it.stat(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun symlink(link: Path, target: String) {
        val client = getClient(link.authority)
        try {
            client.symlink(link.remotePath, target)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        LocalWatchService.onEntryCreated(link as Java8Path)
    }

    @Throws(ClientException::class)
    fun unlink(path: Path) {
        val client = getClient(path.authority)
        try {
            client.rm(path.remotePath)
        } catch (e: IOException) {
            throw ClientException(e)
        }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    /**
     * Runs a read-only call. If the connection turns out to be dead (anything but an answer from
     * the server), drops it and tries once more on a fresh one.
     */
    @Throws(ClientException::class)
    private inline fun <T> withClient(authority: Authority, block: (SFTPClient) -> T): T {
        val client = getClient(authority)
        return try {
            block(client)
        } catch (e: SFTPException) {
            throw ClientException(e)
        } catch (e: IOException) {
            dropClient(authority, client)
            try {
                block(getClient(authority))
            } catch (e2: IOException) {
                throw ClientException(e2)
            }
        }
    }

    private fun dropClient(authority: Authority, client: SFTPClient) {
        val sshClient = synchronized(clients) {
            if (clients[authority] !== client) {
                // Another thread already replaced it.
                return
            }
            clients -= authority
            sshClients.remove(authority)
        }
        client.closeSafe()
        sshClient?.closeSafe()
    }

    @Throws(ClientException::class)
    private fun getClient(authority: Authority): SFTPClient {
        synchronized(clients) {
            var client = clients[authority]
            if (client != null) {
                if (client.sftpEngine.subsystem.isOpen) {
                    return client
                } else {
                    client.closeSafe()
                    clients -= authority
                    sshClients.remove(authority)?.closeSafe()
                }
            }
            val authentication = authenticator.getAuthentication(authority)
                ?: throw ClientException("No authentication found for $authority")
            // Satria build: an encrypted key saved without its passphrase asks for it on every
            // new connection, before the network connection is opened.
            val authMethod = if (
                authentication is PublicKeyAuthentication &&
                authentication.privateKeyPassword == null &&
                PublicKeyAuthentication.isEncrypted(authentication.privateKey)
            ) {
                val passphrase =
                    SftpPassphrasePrompt.request(authority, authentication.privateKey)
                PublicKeyAuthentication(authentication.privateKey, passphrase).toAuthMethod()
            } else {
                authentication.toAuthMethod()
            }
            val hostKeyVerifier = KnownHosts.Verifier()
            // Satria build: mobile networks drop idle connections silently. Keep-alives keep the
            // connection open, and detect a dead one so that the next call reconnects.
            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
            val sshClient = SSHClient(config).apply {
                addHostKeyVerifier(hostKeyVerifier)
                connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
                (connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEP_ALIVE_MAX_COUNT
            }
            try {
                sshClient.connect(authority.host, authority.port)
            } catch (e: IOException) {
                sshClient.closeSafe()
                val mismatch = hostKeyVerifier.mismatch
                if (mismatch != null) {
                    throw ClientException(
                        application.getString(
                            R.string.sftp_host_key_changed_format, authority.host, authority.port,
                            mismatch.pinned, mismatch.presented
                        ), e
                    )
                }
                throw ClientException(e)
            }
            try {
                sshClient.auth(authority.username, authMethod)
            } catch (e: UserAuthException) {
                sshClient.closeSafe()
                throw ClientException(e)
            } catch (e: TransportException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            client = try {
                sshClient.newSFTPClient()
            } catch (e: IOException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            clients[authority] = client
            sshClients[authority] = sshClient
            return client
        }
    }

    private const val KEEP_ALIVE_INTERVAL_SECONDS = 15
    private const val KEEP_ALIVE_MAX_COUNT = 3

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
