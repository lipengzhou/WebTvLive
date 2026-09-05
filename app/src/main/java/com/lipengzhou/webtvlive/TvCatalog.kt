package com.lipengzhou.webtvlive

/**
 * 频道目录：按「分类 -> 频道」两级组织。
 *
 * 目前仅有 CCTV 一个分类，后续新增「省份卫视」等分类时，只需往 [CATEGORIES] 里追加即可，
 * 侧边菜单与遥控器换台逻辑都基于这里的数据自动生成，无需改动 UI 代码。
 *
 * 说明：
 *  - 上/下遥控器换台走 [flatChannels]（把所有分类的频道按顺序拉平的一维表），
 *    这样换台顺序、以及 SharedPreferences 里存的「上次频道下标」都保持与旧版一致。
 *  - 侧边菜单选台走「分类下标 + 分类内频道下标」，再用 [flatIndexOf] 换算成一维下标去加载。
 */

/** 一个频道：显示名 + 官网直播页 URL。 */
data class Channel(val name: String, val url: String)

/** 一个分类：分类名 + 该分类下的频道列表。 */
data class Category(val name: String, val channels: List<Channel>)

object TvCatalog {

    /** 分类表（顺序即菜单左栏从上到下的展示顺序）。 */
    val categories: List<Category> = listOf(
        Category(
            name = "CCTV",
            channels = listOf(
                Channel("CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"),
                Channel("CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"),
                Channel("CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"),
                Channel("CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"),
                Channel("CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"),
                Channel("CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
                Channel("CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"),
                Channel("CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
                Channel("CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"),
                Channel("CCTV-9 纪录", "https://tv.cctv.com/live/cctvjilu/"),
                Channel("CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"),
                Channel("CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"),
                Channel("CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
                Channel("CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"),
                Channel("CCTV-14 少儿", "https://tv.cctv.com/live/cctvchild/"),
                Channel("CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"),
                Channel("CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
                Channel("CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"),
            ),
        ),
    )

    /** 所有频道按分类顺序拉平成一维表：供遥控器上/下循环换台与历史下标兼容使用。 */
    val flatChannels: List<Channel> = categories.flatMap { it.channels }

    /** 把「分类下标 + 分类内频道下标」换算成一维下标。 */
    fun flatIndexOf(categoryIndex: Int, channelIndex: Int): Int {
        var base = 0
        for (i in 0 until categoryIndex) base += categories[i].channels.size
        return base + channelIndex
    }

    /**
     * 把一维下标反查成「分类下标 + 分类内频道下标」。
     * 用于打开菜单时把当前正在播放的频道高亮到对应分类/频道行。
     */
    fun locate(flatIndex: Int): Pair<Int, Int> {
        var remaining = flatIndex
        for (i in categories.indices) {
            val size = categories[i].channels.size
            if (remaining < size) return i to remaining
            remaining -= size
        }
        return 0 to 0
    }
}
