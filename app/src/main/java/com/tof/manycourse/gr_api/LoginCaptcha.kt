package com.tof.manycourse.gr_api

/**
 * 一次需要人工识别的登录验证码。
 *
 * 为什么这个类型必须存在：广州软件学院的统一身份认证（金智 CAS）对**每一次**
 * 账号密码登录都要求填验证码（服务端 `loginType` 接口返回 `isVerifyCode: "1"`，
 * 与"错了几次才要"无关），而验证码是一张**算术题图片**（100×25 的 PNG，
 * 答案是图上算式的得数）。图片只能由人来看，所以登录必须能"分两步走"：
 *
 * ```
 *   login(账号, 密码)                     ← captcha = null
 *        ↓ 服务端回 CODEFALSE
 *   LoginResult.NeedCaptcha(图 + token)   →  界面把图显示出来，用户填得数
 *        ↓
 *   login(账号, 密码, captcha = LoginCaptcha(token, 图, 得数))
 *        ↓
 *   Success
 * ```
 *
 * @param token 服务端给的验证码标识（CAS 里是 `uid`），重新提交时要原样带回
 * @param imageBase64 验证码图片，**完整的 data URI**（`data:image/png;base64,…`）
 *   或纯 base64 都可以
 * @param answer 用户看图后填的答案。**只有回传时才有值**；
 *   服务端第一次返回验证码时它是空串
 */
data class LoginCaptcha(
    val token: String,
    val imageBase64: String,
    val answer: String = "",
) {
    /** 去掉 `data:image/…;base64,` 前缀后的纯 base64（给 `Base64.decode` 用） */
    val rawBase64: String
        get() = imageBase64.substringAfter("base64,", imageBase64)

    /** 用户还没填答案 —— 界面据此决定"要不要弹出验证码输入" */
    val isAnswered: Boolean get() = answer.isNotBlank()
}
