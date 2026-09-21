package com.blive.tv.data.update

/** 更新源 */
enum class UpdateSource(val displayName: String) {
    GITHUB("GitHub"),
    GITEE("Gitee");

    companion object {
        const val OWNER = "Kola96"
        const val REPO = "BLive"
    }
}
