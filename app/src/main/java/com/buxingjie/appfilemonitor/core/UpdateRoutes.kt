package com.buxingjie.appfilemonitor.core

/**
 * 更新线路：5 条可自由切换的线路。
 * 0 = 自动（依次尝试全部线路），1 = GitHub 官方直连，2/3/4 = 三个加速镜像。
 */
object UpdateRoutes {

    data class Route(val id: Int, val label: String, val host: String)

    private const val GH_PROXY = "https://gh-proxy.com/"
    private const val GHPROXY = "https://ghproxy.net/"
    private const val GHFAST = "https://ghfast.top/"

    val ROUTES = listOf(
        Route(0, "自动（智能选线）", ""),
        Route(1, "GitHub 官方直连", ""),
        Route(2, "gh-proxy 加速", GH_PROXY),
        Route(3, "ghproxy 加速", GHPROXY),
        Route(4, "ghfast 加速", GHFAST)
    )

    /** 自动模式下的尝试顺序：镜像优先，直连兜底 */
    private val AUTO_HOSTS = listOf(GH_PROXY, GHPROXY, GHFAST, "")

    fun label(id: Int): String =
        ROUTES.firstOrNull { it.id == id }?.label ?: ROUTES[0].label

    /** 版本信息（静态 version.json，不走 GitHub API，不会 403 限流） */
    fun versionJsonUrls(routeId: Int, repo: String): List<String> {
        val direct = "https://raw.githubusercontent.com/$repo/main/docs/version.json"
        return urls(routeId) { host -> if (host.isEmpty()) direct else host + direct }
    }

    fun apiUrls(routeId: Int, repo: String): List<String> {
        val direct = "https://api.github.com/repos/$repo/releases/latest"
        return urls(routeId) { host -> if (host.isEmpty()) direct else host + direct }
    }

    fun fileUrls(routeId: Int, rawUrl: String): List<String> =
        urls(routeId) { host -> if (host.isEmpty()) rawUrl else host + rawUrl }

    private fun urls(routeId: Int, build: (String) -> String): List<String> {
        val route = ROUTES.firstOrNull { it.id == routeId } ?: ROUTES[0]
        return if (route.id == 0) {
            AUTO_HOSTS.map(build)
        } else {
            listOf(build(route.host))
        }
    }
}
