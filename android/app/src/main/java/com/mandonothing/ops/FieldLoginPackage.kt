package com.mandonothing.ops

import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream

internal data class FieldLoginPackage(val server: String, val invite: String) {
    fun json() = JSONObject().put("type", TYPE).put("version", 1).put("server", server).put("invite", invite).toString()

    companion object {
        const val MIME = "application/vnd.protocolx.login+json"
        private const val TYPE = "protocol-x-login"
        private const val MAX_BYTES = 2048

        fun intentUri(intent: Intent): Uri? =
            intent.data?.takeIf { intent.action == Intent.ACTION_VIEW && intent.type == MIME && it.scheme == "content" }

        fun read(stream: InputStream, trustedServers: Set<String>): FieldLoginPackage {
            val bytes = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val count = stream.read(bytes, size, bytes.size - size)
                if (count <= 0) break
                size += count
            }
            require(size <= MAX_BYTES) { "登录包太大" }
            val data = JSONObject(bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true))
            require(data.getString("type") == TYPE && data.getInt("version") == 1) { "此文件不是 Protocol X 登录包" }
            val server = data.getString("server")
            val invite = data.getString("invite")
            require(server in trustedServers) { "登录包指向未配置的服务器，请联系调度员" }
            require(invite.matches(Regex("^[A-Za-z0-9_-]{22,128}$"))) { "登录包无效，请联系调度员" }
            return FieldLoginPackage(server, invite)
        }
    }
}
