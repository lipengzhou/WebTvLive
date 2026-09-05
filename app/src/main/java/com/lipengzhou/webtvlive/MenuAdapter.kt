package com.lipengzhou.webtvlive

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 侧边菜单通用列表适配器：左栏（分类）和右栏（频道）共用一套。
 *
 * 关键点——菜单导航不依赖 Android 的原生焦点系统：
 *  方向键在 [MainActivity] 里被 dispatchKeyEvent 提前拦截（否则会被 WebView 吃掉），
 *  所以这里只按外部传入的「选中下标 + 本列是否为当前活动列」来渲染高亮：
 *   - 当前活动列的选中行 -> activated（高亮蓝，相当于焦点所在）；
 *   - 非活动列的选中行   -> selected（暗选中态，提示这一列停在哪）。
 *
 * @param itemLayoutRes 行布局（分类/频道各一份），根节点即 TextView
 * @param onItemClick   点击某行回调（触屏用；遥控器 OK 走 MainActivity 直接读选中下标）
 */
class MenuAdapter(
    private val itemLayoutRes: Int,
    private val onItemClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<MenuAdapter.VH>() {

    private var titles: List<String> = emptyList()

    /** 当前选中行下标。 */
    var selectedIndex: Int = 0
        private set

    /** 本列是否为当前活动列（决定选中行用高亮还是暗选中态）。 */
    private var columnActive: Boolean = false

    class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    /** 换分类等场景整列替换数据；[keepIndex] 为替换后要选中的行。 */
    fun submit(newTitles: List<String>, keepIndex: Int) {
        titles = newTitles
        selectedIndex = keepIndex.coerceIn(0, (newTitles.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    /** 更新选中行；返回是否真的变化（避免无谓刷新）。 */
    fun setSelected(index: Int) {
        if (index == selectedIndex || index !in titles.indices) return
        val old = selectedIndex
        selectedIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    /** 切换本列活动状态，刷新选中行的高亮样式。 */
    fun setColumnActive(active: Boolean) {
        if (active == columnActive) return
        columnActive = active
        if (selectedIndex in titles.indices) notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(itemLayoutRes, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.textView.text = titles[position]
        holder.textView.isActivated = columnActive && position == selectedIndex
        holder.textView.isSelected = !columnActive && position == selectedIndex
        holder.textView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = titles.size
}
