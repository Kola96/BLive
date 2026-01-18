package com.blive.tv.danmu

/**
 * 弹幕消息数据模型
 */
sealed class DanmuMessage {
    /**
     * 普通弹幕消息
     */
    data class Danmu(
        val userId: Long,
        val username: String,
        val content: String,
        val color: Int = 0xFFFFFF,
        val mode: Int = 1, // 1:滚动 4:顶部 5:底部
        val fontSize: Int = 25,
        val timestamp: Long = System.currentTimeMillis()
    ) : DanmuMessage()

    /**
     * 礼物消息
     */
    data class Gift(
        val userId: Long,
        val username: String,
        val giftName: String,
        val giftCount: Int,
        val giftPrice: Int
    ) : DanmuMessage()

    /**
     * 进入房间消息
     */
    data class EnterRoom(
        val userId: Long,
        val username: String,
        val isVip: Boolean = false
    ) : DanmuMessage()

    /**
     * 其他消息
     */
    data class Other(
        val cmd: String,
        val data: Map<String, Any?>
    ) : DanmuMessage()
}
