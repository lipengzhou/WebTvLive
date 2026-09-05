package com.lipengzhou.webtvlive

/**
 * 频道目录：按「分类 -> 频道」两级组织。
 *
 * 频道全部来自央视频电视直播页。CCTV 与卫视在同一个单页应用中，切台时由 WebExtension
 * 按 [Channel.siteName] 找到网站频道项并触发点击，不需要重新加载整页。
 *
 * 说明：
 *  - 上/下遥控器换台走 [flatChannels]（把所有分类的频道按顺序拉平的一维表），
 *    这样换台顺序、以及 SharedPreferences 里存的「上次频道下标」都保持与旧版一致。
 *  - 侧边菜单选台走「分类下标 + 分类内频道下标」，再用 [flatIndexOf] 换算成一维下标去加载。
 */

/** 一个频道：App 内显示名 + 央视频网页中的精确频道名。 */
data class Channel(val name: String, val siteName: String)

/** 一个分类：分类名 + 该分类下的频道列表。 */
data class Category(val name: String, val channels: List<Channel>)

object TvCatalog {
    const val YANGSHIPIN_HOME_URL = "https://www.yangshipin.cn/tv/home"

    /** 分类表（顺序即菜单左栏从上到下的展示顺序）。 */
    val categories: List<Category> = listOf(
        Category(
            name = "CCTV",
            channels = listOf(
                Channel("CCTV-1 综合", "CCTV1"),
                Channel("CCTV-2 财经", "CCTV2"),
                Channel("CCTV-3 综艺", "CCTV3"),
                Channel("CCTV-4 中文国际", "CCTV4"),
                Channel("CCTV-5 体育", "CCTV5"),
                Channel("CCTV-5+ 体育赛事", "CCTV5+"),
                Channel("CCTV-6 电影", "CCTV6"),
                Channel("CCTV-7 国防军事", "CCTV7"),
                Channel("CCTV-8 电视剧", "CCTV8"),
                Channel("CCTV-9 纪录", "CCTV9"),
                Channel("CCTV-10 科教", "CCTV10"),
                Channel("CCTV-11 戏曲", "CCTV11"),
                Channel("CCTV-12 社会与法", "CCTV12"),
                Channel("CCTV-13 新闻", "CCTV13"),
                Channel("CCTV-14 少儿", "CCTV14"),
                Channel("CCTV-15 音乐", "CCTV15"),
                Channel("CCTV-16 奥林匹克", "CCTV16-HD"),
                Channel("CCTV-17 农业农村", "CCTV17"),
            ),
        ),
        Category(
            name = "卫视",
            channels = listOf(
                Channel("北京卫视", "北京卫视"),
                Channel("江苏卫视", "江苏卫视"),
                Channel("东方卫视", "东方卫视"),
                Channel("浙江卫视", "浙江卫视"),
                Channel("湖南卫视", "湖南卫视"),
                Channel("湖北卫视", "湖北卫视"),
                Channel("广东卫视", "广东卫视"),
                Channel("广西卫视", "广西卫视"),
                Channel("黑龙江卫视", "黑龙江卫视"),
                Channel("海南卫视", "海南卫视"),
                Channel("重庆卫视", "重庆卫视"),
                Channel("深圳卫视", "深圳卫视"),
                Channel("四川卫视", "四川卫视"),
                Channel("河南卫视", "河南卫视"),
                Channel("福建东南卫视", "福建东南卫视"),
                Channel("贵州卫视", "贵州卫视"),
                Channel("江西卫视", "江西卫视"),
                Channel("辽宁卫视", "辽宁卫视"),
                Channel("安徽卫视", "安徽卫视"),
                Channel("河北卫视", "河北卫视"),
                Channel("山东卫视", "山东卫视"),
                Channel("天津卫视", "天津卫视"),
                Channel("吉林卫视", "吉林卫视"),
                Channel("陕西卫视", "陕西卫视"),
                Channel("甘肃卫视", "甘肃卫视"),
                Channel("宁夏卫视", "宁夏卫视"),
                Channel("内蒙古卫视", "内蒙古卫视"),
                Channel("云南卫视", "云南卫视"),
                Channel("山西卫视", "山西卫视"),
                Channel("青海卫视", "青海卫视"),
                Channel("西藏卫视", "西藏卫视"),
                Channel("中国教育电视台1频道", "中国教育电视台1频道"),
                Channel("新疆卫视", "新疆卫视"),
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
