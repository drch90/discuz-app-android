package com.discuz.mobile

data class LoginQr(val siteId: String, val uid: Int, val key: String) {
    companion object {
        fun parse(value: String): LoginQr {
            val match = Regex("^discuz-app-login:v1:([a-f0-9]{64}):([1-9][0-9]{0,9}):([a-f0-9]{64})$").matchEntire(value)
                ?: throw IllegalArgumentException("请扫描电脑端个人设置中生成的 App 登录二维码。")
            val uid = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("登录二维码格式不正确。")
            return LoginQr(match.groupValues[1], uid, match.groupValues[3])
        }
    }
}
