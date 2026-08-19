package com.daidai.panel

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class AndroidSandboxManager(
    private val context: Context,
    private val panelDir: File,
    private val onLog: (level: String, tag: String, msg: String) -> Unit
) {
    companion object {
        private const val TAG = "DaidaiSandbox"
        private const val SANDBOX_VERSION = "alpine-3.21.3-proot"
        private const val ROOTFS_GZ_ASSET = "alpine-minirootfs.tar.gz"
        private const val ROOTFS_TAR_ASSET = "alpine-minirootfs.tar"
        private const val ALPINE_VERSION = "v3.21"
        private const val ALPINE_MIRROR = "https://repo.huaweicloud.com/alpine"
        private const val PIP_INDEX_URL = "https://mirrors.aliyun.com/pypi/simple/"
        private const val NPM_REGISTRY = "https://registry.npmmirror.com"
    }

    val sandboxDir: File = File(panelDir, "sandbox")
    val rootfsDir: File = File(sandboxDir, "rootfs")
    val tmpDir: File = File(sandboxDir, "tmp")
    val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    val prootLoader: File = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    val prootLoader32: File = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so")
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    fun installIfNeeded() {
        sandboxDir.mkdirs()
        tmpDir.mkdirs()

        if (!prootBinary.exists() || !prootBinary.canExecute()) {
            throw IllegalStateException("PRoot binary missing at ${prootBinary.absolutePath}")
        }
        if (!prootLoader.exists() || !prootLoader32.exists()) {
            throw IllegalStateException("PRoot loaders missing in ${nativeLibDir.absolutePath}")
        }

        val versionFile = File(rootfsDir, ".daidai-sandbox-version")
        if (rootfsDir.exists() && versionFile.exists() && versionFile.readText().trim() == SANDBOX_VERSION) {
            log("I", "Linux sandbox already installed")
            refreshDns()
            ensurePanelDirectories()
            return
        }

        if (rootfsDir.exists() && rootfsDir.list()?.isNotEmpty() == true) {
            throw IllegalStateException("Sandbox rootfs version mismatch. Please clear app data before installing the new sandbox runtime.")
        }

        log("I", "Installing Linux sandbox runtime...")
        rootfsDir.mkdirs()

        val assetName = selectRootfsAsset()
        context.assets.open(assetName).use { raw ->
            val stream: InputStream = if (assetName.endsWith(".gz")) GZIPInputStream(raw) else raw
            stream.use { extractTar(it, rootfsDir) }
        }

        ensurePanelDirectories()
        refreshDns()
        writeMirrorConfigs()
        installBasePackages()
        versionFile.writeText(SANDBOX_VERSION)
        log("I", "Linux sandbox installed at ${rootfsDir.absolutePath}")
    }

    fun mountSpec(dataDir: File, scriptsDir: File, logDir: File): String {
        val mounts = listOf(
            dataDir.absolutePath to "/panel/data",
            scriptsDir.absolutePath to "/panel/scripts",
            logDir.absolutePath to "/panel/logs",
            tmpDir.absolutePath to "/tmp",
        )
        return mounts.joinToString("|") { (host, guest) -> "$host:$guest" }
    }

    private fun selectRootfsAsset(): String {
        return try {
            context.assets.open(ROOTFS_GZ_ASSET).close()
            ROOTFS_GZ_ASSET
        } catch (_: Exception) {
            context.assets.open(ROOTFS_TAR_ASSET).close()
            ROOTFS_TAR_ASSET
        }
    }

    private fun ensurePanelDirectories() {
        listOf(
            "panel/data",
            "panel/scripts",
            "panel/logs",
            "root",
            "tmp",
            "opt/bin",
        ).forEach { File(rootfsDir, it).mkdirs() }
    }

    private fun refreshDns() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    private fun writeMirrorConfigs() {
        val repositories = File(rootfsDir, "etc/apk/repositories")
        repositories.parentFile?.mkdirs()
        repositories.writeText(
            "$ALPINE_MIRROR/$ALPINE_VERSION/main\n" +
                "$ALPINE_MIRROR/$ALPINE_VERSION/community\n"
        )

        val pipConf = File(rootfsDir, "root/.pip/pip.conf")
        pipConf.parentFile?.mkdirs()
        pipConf.writeText(
            "[global]\n" +
                "index-url = $PIP_INDEX_URL\n" +
                "trusted-host = mirrors.aliyun.com\n"
        )

        val npmrc = File(rootfsDir, "root/.npmrc")
        npmrc.parentFile?.mkdirs()
        npmrc.writeText("registry=$NPM_REGISTRY\n")
    }

    private fun installBasePackages() {
        val packageReady = listOf(
            "usr/bin/python3",
            "usr/bin/node",
            "bin/bash",
            "usr/bin/git",
            "usr/bin/go",
        ).all { File(rootfsDir, it).exists() }
        if (packageReady) return

        log("I", "Installing sandbox packages: python3 nodejs bash go git curl")
        writeMirrorConfigs()
        val command = "apk update && apk add --no-cache python3 py3-pip nodejs npm bash go git curl ca-certificates coreutils && npm config set registry $NPM_REGISTRY"
        val result = runProot(command, timeoutMs = 10L * 60L * 1000L)
        if (result != 0) {
            throw IllegalStateException("Sandbox package install failed with exit code $result")
        }
    }

    private fun runProot(command: String, timeoutMs: Long): Int {
        val args = listOf(
            prootBinary.absolutePath,
            "-0",
            "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/sh", "-c", command,
        )
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
                environment()["PROOT_LOADER"] = prootLoader.absolutePath
                environment()["PROOT_LOADER_32"] = prootLoader32.absolutePath
            }
            .start()

        val logThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { log("I", it) }
            }
        }
        logThread.start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            log("E", "Sandbox command timed out")
            return -1
        }
        logThread.join(1000)
        return process.exitValue()
    }

    private fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)
        while (true) {
            val read = input.readFullyOrEnd(header)
            if (read == -1 || header.all { it == 0.toByte() }) return
            if (read != 512) throw IllegalStateException("Invalid tar header")

            val name = parseTarString(header, 0, 100)
            if (name.isBlank()) return
            val size = parseTarOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = parseTarString(header, 157, 100)
            val outFile = safeTarget(targetDir, name)

            when (type) {
                '5' -> outFile.mkdirs()
                '2' -> createSymlink(outFile, linkName)
                '1' -> createHardlinkOrCopy(targetDir, outFile, linkName)
                else -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> input.copyFixedTo(output, size) }
                    outFile.setReadable(true, false)
                    val mode = parseTarOctal(header, 100, 8)
                    if (mode and 73L != 0L) outFile.setExecutable(true, false)
                }
            }

            skipPadding(input, size)
        }
    }

    private fun safeTarget(base: File, entryName: String): File {
        val target = File(base, entryName).canonicalFile
        val basePath = base.canonicalFile.toPath()
        if (!target.toPath().startsWith(basePath)) {
            throw IllegalStateException("Unsafe tar entry: $entryName")
        }
        return target
    }

    private fun createSymlink(link: File, target: String) {
        if (target.isBlank()) return
        link.parentFile?.mkdirs()
        runCatching { Files.createSymbolicLink(link.toPath(), File(target).toPath()) }
            .onFailure { Log.w(TAG, "Failed to create symlink ${link.absolutePath} -> $target", it) }
    }

    private fun createHardlinkOrCopy(base: File, link: File, target: String) {
        if (target.isBlank()) return
        val targetFile = safeTarget(base, target)
        link.parentFile?.mkdirs()
        runCatching { Files.createLink(link.toPath(), targetFile.toPath()) }
            .recoverCatching { targetFile.copyTo(link, overwrite = true) }
            .onFailure { Log.w(TAG, "Failed to create hardlink ${link.absolutePath} -> $target", it) }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count == -1) return if (offset == 0) -1 else offset
            offset += count
        }
        return offset
    }

    private fun InputStream.copyFixedTo(output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (count == -1) throw IllegalStateException("Unexpected EOF in tar entry")
            output.write(buf, 0, count)
            remaining -= count
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (512 - (size % 512)) % 512
        var remaining = padding
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return header.copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val raw = parseTarString(header, offset, length).trim()
        if (raw.isEmpty()) return 0
        return raw.toLongOrNull(8) ?: 0
    }

    private fun log(level: String, msg: String) {
        Log.i(TAG, msg)
        onLog(level, TAG, msg)
    }
}
