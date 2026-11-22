package com.example.autotapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.autotapper.Ef
import com.example.autotapper.EfBlock
import com.example.autotapper.LogBus
import com.example.autotapper.R
import com.example.autotapper.engine.RegionStore
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把插件声明的 ScreenSpec 渲染成终末地风味的表单，并把界面上的值写回一份新的 JSONObject。
 *
 * 设计要点：
 * - Binder 只持有「值 + 一份 config 快照」，不持有 Activity / Fragment / 任何 View，界面销毁后也不会泄漏；
 * - 不修改传进来的 config：所有回填都从它读，flush() 永远返回新的 JSONObject；
 * - 插件给的 spec 不可信：未知字段跳过并写 LogBus，坏数据一律兜底默认值，不抛到界面。
 */
class EfSpecRenderer(private val context: Context) {

    /** 把界面上的值写回一份新的 JSONObject；不修改渲染时传入的 config。 */
    interface Binder {
        fun flush(): JSONObject
    }

    /**
     * 渲染一份 spec。容器必须支持纵向追加子 View（LinearLayout 或 ScrollView 里的 LinearLayout）。
     */
    fun render(container: ViewGroup, spec: ScreenSpec, config: JSONObject): Binder {
        val out = Values(config)
        try {
            val sections = spec.sections
            if (sections.isEmpty()) {
                container.addView(EfHatch(context, context.getString(R.string.plugin_spec_empty), 96))
                return out
            }
            sections.forEachIndexed { index, section ->
                runCatching { addSection(container, section, index, config, out) }
                    .onFailure { t -> LogBus.warn("设置页分区渲染失败：" + describe(t)) }
            }
        } catch (t: Throwable) {
            LogBus.warn("设置页渲染失败：" + describe(t))
        }
        return out
    }

    private fun addSection(
        container: ViewGroup,
        section: Section,
        index: Int,
        config: JSONObject,
        out: Values
    ) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    topMargin = Ef.dp(context, 24)
                }
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val eyebrow = Ef.eyebrow(context, safeSectionText(section.eyebrow, "// SECTION " + (index + 1)))
        eyebrow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        header.addView(eyebrow)
        val title = Ef.heading(context, safeSectionText(section.title, ""), 17f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(context, 3) }
        }
        header.addView(title)
        block.addView(header)
        block.addView(rule().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ef.dp(context, 1)
            ).apply { topMargin = Ef.dp(context, 8) }
        })

        section.fields.forEach { field ->
            runCatching { addField(block, field, config, out) }
                .onFailure { t -> LogBus.warn("设置项渲染失败，已跳过：" + describe(t)) }
        }
        container.addView(block)
    }

    /** 一个字段 = 中文标签 + 控件 + 紧贴下沿的细线。 */
    private fun fieldBlock(label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ef.dp(context, 16) }
        addView(TextView(context).apply {
            text = label.ifEmpty { "-" }
            textSize = 13f
            setTextColor(Ef.color(context, R.color.ef_ink))
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ef.dp(context, 7) }
        })
    }

    /** 控件加完后统一补一条细线，跟 EfFieldRow 的分区方式保持一致。 */
    private fun finishField(row: LinearLayout) {
        row.addView(rule().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ef.dp(context, 1)
            ).apply { topMargin = Ef.dp(context, 10) }
        })
    }

    private fun rule(): View = View(context).apply {
        setBackgroundColor(Ef.color(context, R.color.ef_rule))
    }

    private fun addField(parent: LinearLayout, field: Field, config: JSONObject, out: Values) {
        // 插件可能给出恶意/坏数据字段，所有 getter 都按跨边界调用兜住。
        val key = runCatching { field.key }.getOrDefault("")
        val label = runCatching { field.label }.getOrDefault("")
        if (key.isEmpty()) {
            LogBus.warn("设置项缺少 key，已跳过：" + label)
            return
        }
        when (field) {
            is Field.Text -> addText(parent, field, config, out)
            is Field.IntField -> addInt(parent, field, config, out)
            is Field.NumberField -> addNumber(parent, field, config, out)
            is Field.RangeField -> addRange(parent, field, config, out)
            is Field.SwitchField -> addSwitch(parent, field, config, out)
            is Field.EnumField -> addEnum(parent, field, config, out)
            is Field.RegionPicker -> addRegionPicker(parent, field, config, out)
            is Field.ColorField -> addColor(parent, field, config, out)
            is Field.TextListField -> addTextList(parent, field, config, out)
            is Field.StepIndexField -> addStepIndex(parent, field, config, out)
            else -> LogBus.warn("未知的设置项类型，已跳过：" + key)
        }
    }

    private fun addText(parent: LinearLayout, f: Field.Text, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        val edit = input(config.optString(f.key, ""), f.placeholder.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (f.multiline) {
                isSingleLine = false
                maxLines = 5
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        edit.watch { out.put(f.key, it) }
        row.addView(edit)
        finishField(row)
        parent.addView(row)
    }

    private fun addInt(parent: LinearLayout, f: Field.IntField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        var warned = false
        val lo = f.min.coerceAtMost(f.max)
        val hi = f.max.coerceAtLeast(f.min)
        val initial = readLongSafely(config, f.key, lo).coerceIn(lo, hi)
        val edit = input(initial.toString(), "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val holder = horizontalRow()
        holder.addView(edit)
        unitView(f.unit)?.let { holder.addView(it) }
        row.addView(holder)
        edit.watch { raw ->
            val parsed = raw.toLongOrNull() ?: lo
            val value = parsed.coerceIn(lo, hi)
            if (value != parsed && !warned) {
                warned = true
                toast(context.getString(R.string.plugin_spec_coerced, lo, hi))
            }
            out.put(f.key, value)
        }
        finishField(row)
        parent.addView(row)
    }

    private fun addNumber(parent: LinearLayout, f: Field.NumberField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        val initial = readDoubleSafely(config, f.key, 0.0)
        val edit = input(doubleToInput(initial, f.decimals), "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        edit.watch { raw ->
            val value = raw.toDoubleOrNull() ?: 0.0
            val lo = f.min.coerceAtMost(f.max)
            val hi = f.max.coerceAtLeast(f.min)
            out.put(f.key, value.coerceIn(lo, hi))
        }
        row.addView(edit)
        finishField(row)
        parent.addView(row)
    }

    private fun addRange(parent: LinearLayout, f: Field.RangeField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        val initial = readRange(config, f.key, f.min, f.max)
        // EfRangeInput 是 final 类且内部 EditText 是私有的，所以不能继承、也挂不上文本监听。
        // 做法：初始值先写一份，真正的最新值在 flush() 那一刻用 getRange() 读回（Values.addFlusher）。
        val range = EfRangeInput(context, initial.first, initial.second).apply {
            setUnit(f.unit)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        out.putRange(f.key, initial.first, initial.second)
        out.addFlusher { sink ->
            val pair = range.getRange()
            val lo = f.min.coerceAtMost(f.max)
            val hi = f.max.coerceAtLeast(f.min)
            var min = pair.first.coerceIn(lo, hi)
            var max = pair.second.coerceIn(lo, hi)
            if (pair.first != min || pair.second != max) {
                toast(context.getString(R.string.plugin_spec_coerced, lo, hi))
                range.setRange(min, max)
            }
            if (min > max) {
                val swap = min
                min = max
                max = swap
                range.setRange(min, max)
            }
            // 契约第 8 节：区间存 {"min","max"}，值必须是 Long
            sink.put(f.key, JSONObject().put("min", min).put("max", max))
        }
        row.addView(range)
        finishField(row)
        parent.addView(row)
    }

    private fun addSwitch(parent: LinearLayout, f: Field.SwitchField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        var on = if (config.has(f.key)) {
            runCatching { config.getBoolean(f.key) }.getOrDefault(f.defaultValue)
        } else {
            f.defaultValue
        }
        // 墨底反白 / 白底细线两态切换，用 Ef 的切角块和 ef_* 颜色，不发明新颜色。
        val block = TextView(context).apply {
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER
            includeFontPadding = false
            minimumHeight = Ef.dp(context, 48)
            setPadding(Ef.dp(context, 14), Ef.dp(context, 10), Ef.dp(context, 14), Ef.dp(context, 10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun applyState() {
            block.background = if (on) {
                Ef.cutBackground(context, R.color.ef_ink, null, cutDp = Ef.CUT_DP)
            } else {
                Ef.cutBackground(context, R.color.ef_panel, R.color.ef_rule, cutDp = Ef.CUT_DP)
            }
            block.setTextColor(Ef.color(context, if (on) R.color.ef_paper else R.color.ef_ink))
            block.text = if (on) {
                context.getString(R.string.plugin_spec_switch_on)
            } else {
                context.getString(R.string.plugin_spec_switch_off)
            }
            block.contentDescription = block.text
        }
        out.put(f.key, on)
        applyState()
        block.setOnClickListener {
            on = !on
            applyState()
            out.put(f.key, on)
        }
        row.addView(block)
        finishField(row)
        parent.addView(row)
    }

    private fun addEnum(parent: LinearLayout, f: Field.EnumField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        // values 长度与 options 不一致时按显示项数处理，存盘退回显示文案，避免错位写值。
        val aligned = f.values?.takeIf { it.size == f.options.size }
        val options = f.options.ifEmpty { listOf(context.getString(R.string.plugin_spec_empty_option)) }
        val segmented = EfSegmented(context, options)
        val raw = config.optString(f.key, "")
        val storedIndex = aligned?.indexOf(raw) ?: -1
        val optionIndex = options.indexOf(raw)
        val initial = when {
            storedIndex >= 0 -> storedIndex
            optionIndex >= 0 -> optionIndex
            else -> 0
        }
        fun write(index: Int) {
            val value = if (aligned != null && index in aligned.indices) aligned[index] else options[index]
            out.put(f.key, value)
        }
        write(initial)
        segmented.onSelected = { index -> write(index) }
        segmented.setSelected(initial)
        segmented.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.addView(segmented)
        finishField(row)
        parent.addView(row)
    }

    private fun addRegionPicker(
        parent: LinearLayout,
        f: Field.RegionPicker,
        config: JSONObject,
        out: Values
    ) {
        val row = fieldBlock(f.label)
        val regions = runCatching { RegionStore.load(context) }.getOrDefault(emptyList())
        val selected = readRegionIds(config, f.key).toMutableSet()
        if (regions.isEmpty()) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.plugin_spec_no_regions)
                textSize = 13f
                setTextColor(Ef.color(context, R.color.ef_ink2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        val writes = ArrayList<() -> Unit>()
        regions.forEachIndexed { index, region ->
            val id = (index + 1).toString() // 契约第 7 节：写回 1 基序号字符串
            val label = context.getString(
                R.string.plugin_spec_region_row,
                RegionStore.labelFor(index),
                region.w,
                region.h
            )
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = Ef.dp(context, 48)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val mark = TextView(context).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Ef.color(context, R.color.ef_ink))
                layoutParams = LinearLayout.LayoutParams(
                    Ef.dp(context, 36),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val text = TextView(context).apply {
                this.text = label
                textSize = 13f
                setTextColor(Ef.color(context, R.color.ef_ink))
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = Ef.dp(context, 8) }
            }
            line.addView(mark)
            line.addView(text)
            fun applyState() {
                val on = id in selected
                mark.text = if (on) {
                    context.getString(R.string.plugin_spec_check_on)
                } else {
                    context.getString(R.string.plugin_spec_check_off)
                }
                line.background = if (on) {
                    Ef.rectBackground(context, R.color.ef_signal)
                } else {
                    Ef.rectBackground(context, R.color.ef_panel, R.color.ef_rule)
                }
                line.contentDescription = label
            }
            applyState()
            line.setOnClickListener {
                if (id in selected) {
                    if (f.multiple) selected.remove(id) else selected.clear()
                } else {
                    // 单选先清空再重选；写回顺序由 selected 的集合顺序决定，稳定且契约允许。
                    if (!f.multiple) selected.clear()
                    selected.add(id)
                }
                writes.forEach { it() }
                applyState()
            }
            writes.add {
                val array = JSONArray()
                selected.forEach { array.put(it) }
                out.put(f.key, array)
            }
            row.addView(line)
        }
        // 初始化一次：坏数据（无效序号）在这里被过滤掉，flush 不会把它们带给插件。
        selected.retainAll((1..regions.size).map { it.toString() })
        writes.forEach { it() }
        finishField(row)
        parent.addView(row)
    }

    private fun addColor(parent: LinearLayout, f: Field.ColorField, config: JSONObject, out: Values) {
        val row = fieldBlock(f.label)
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(Ef.dp(context, 48), Ef.dp(context, 48))
        }
        val edit = input(normalizeHex(config.optString(f.key, "")), "").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = Ef.dp(context, 10) }
        }
        val swatchDrawable = SwatchDrawable(Ef.color(context, R.color.ef_chip))
        swatch.background = swatchDrawable
        val initial = normalizeHex(edit.text?.toString().orEmpty())
        swatchDrawable.paintColor = parseHex(initial) ?: swatchDrawable.paintColor
        out.put(f.key, initial)
        edit.watch { raw ->
            val value = normalizeHex(raw)
            parseHex(value)?.let { swatchDrawable.paintColor = it }
            out.put(f.key, value)
        }
        val holder = horizontalRow()
        holder.addView(swatch)
        holder.addView(edit)
        row.addView(holder)
        finishField(row)
        parent.addView(row)
    }

    private fun addTextList(
        parent: LinearLayout,
        f: Field.TextListField,
        config: JSONObject,
        out: Values
    ) {
        val row = fieldBlock(f.label)
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(holder)
        val edits = ArrayList<EditText>()
        fun write() {
            val array = JSONArray()
            edits.forEach { array.put(it.text?.toString().orEmpty()) }
            out.put(f.key, array)
        }
        fun addLine(value: String) {
            if (edits.size >= f.maxItems.coerceAtLeast(1)) {
                return
            }
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val edit = input(value, "").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            edit.watch { write() }
            edits.add(edit)
            line.addView(edit)
            val delete = TextView(context).apply {
                text = context.getString(R.string.plugin_spec_delete_row)
                textSize = 12f
                isSingleLine = true
                gravity = Gravity.CENTER
                minimumHeight = Ef.dp(context, 48)
                setPadding(Ef.dp(context, 10), Ef.dp(context, 8), Ef.dp(context, 10), Ef.dp(context, 8))
                setTextColor(Ef.color(context, R.color.ef_danger)) // 红色只给删除
                background = Ef.cutBackground(context, R.color.ef_panel, R.color.ef_rule, cutDp = 6f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 6) }
            }
            delete.setOnClickListener {
                val index = edits.indexOf(edit)
                if (index >= 0) {
                    edits.removeAt(index)
                    holder.removeView(line)
                    write()
                }
            }
            line.addView(delete)
            holder.addView(line)
        }
        val initial = readStringArray(config, f.key)
        if (initial.isEmpty()) addLine("") else initial.forEach { addLine(it) }
        val add = EfBlock(
            context,
            f.addLabel.ifEmpty { context.getString(R.string.plugin_spec_add_row) }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(context, 8) }
        }
        add.setOnClickListener { addLine("") }
        row.addView(add)
        write()
        finishField(row)
        parent.addView(row)
    }

    private fun addStepIndex(
        parent: LinearLayout,
        f: Field.StepIndexField,
        config: JSONObject,
        out: Values
    ) {
        val row = fieldBlock(f.label + "（" + context.getString(R.string.plugin_spec_step_hint) + "）")
        val edit = input(readLongSafely(config, f.key, 1L).toString(), "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        edit.watch { raw ->
            val value = (raw.toIntOrNull() ?: 1).coerceAtLeast(1)
            out.put(f.key, value)
        }
        row.addView(edit)
        finishField(row)
        parent.addView(row)
    }

    // ---------------------------- 小工具 ----------------------------

    private fun input(text: String, hint: String): EditText = EditText(context).apply {
        setText(text)
        this.hint = hint
        textSize = 14f
        setTextColor(Ef.color(context, R.color.ef_ink))
        setHintTextColor(Ef.color(context, R.color.ef_ink2))
        background = Ef.rectBackground(context, R.color.ef_panel, R.color.ef_rule)
        setPadding(Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10))
        minimumHeight = Ef.dp(context, 48)
        includeFontPadding = false
    }

    /** setText 发生在注册监听之前，所以注册后立即主动写一次，保证 out 不漏默认值。 */
    private fun EditText.watch(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                runCatching { block(s?.toString().orEmpty()) }
                    .onFailure { t -> LogBus.warn("读取输入失败：" + describe(t)) }
            }
        })
        runCatching { block(text?.toString().orEmpty()) }
            .onFailure { t -> LogBus.warn("读取初始值失败：" + describe(t)) }
    }

    private fun unitView(unit: String?): TextView? {
        if (unit.isNullOrEmpty()) return null
        return TextView(context).apply {
            text = unit
            textSize = 12f
            setTextColor(Ef.color(context, R.color.ef_ink2))
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ef.dp(context, 8) }
        }
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            .onFailure { t -> LogBus.warn("提示失败：" + describe(t)) }
    }

    private fun readLongSafely(config: JSONObject, key: String, fallback: Long): Long =
        runCatching { config.optLong(key, fallback) }.getOrDefault(fallback)

    private fun readDoubleSafely(config: JSONObject, key: String, fallback: Double): Double =
        runCatching { config.optDouble(key, fallback) }.getOrDefault(fallback)

    /** 兼容对象 {"min","max"} 与纯数字脏数据（契约第 8 节）。 */
    private fun readRange(config: JSONObject, key: String, lo: Long, hi: Long): Pair<Long, Long> {
        var min = lo
        var max = hi
        runCatching {
            when (val raw = config.opt(key)) {
                is JSONObject -> {
                    min = raw.optLong("min", lo)
                    max = raw.optLong("max", hi)
                }
                is Number -> {
                    val value = raw.toLong()
                    min = value
                    max = value
                }
                is String -> {
                    val value = raw.toLongOrNull()
                    if (value != null) {
                        min = value
                        max = value
                    }
                }
            }
        }.onFailure { t -> LogBus.warn("读取区间失败，已按默认值处理：" + describe(t)) }
        min = min.coerceIn(lo, hi)
        max = max.coerceIn(lo, hi)
        if (min > max) {
            val swap = min
            min = max
            max = swap
        }
        return min to max
    }

    private fun readRegionIds(config: JSONObject, key: String): Set<String> = buildSet {
        runCatching {
            when (val raw = config.opt(key)) {
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        val value = raw.opt(i)
                        val id = when (value) {
                            is Number -> value.toInt().toString()
                            is String -> value.trim()
                            else -> null
                        }
                        if (!id.isNullOrEmpty()) add(id)
                    }
                }
                is Number -> add(raw.toInt().toString())
                is String -> if (raw.isNotBlank()) add(raw.trim()) else Unit
                else -> Unit
            }
        }.onFailure { t -> LogBus.warn("读取区域选择失败：" + describe(t)) }
    }

    private fun readStringArray(config: JSONObject, key: String): List<String> = buildList {
        runCatching {
            when (val raw = config.opt(key)) {
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        add(raw.opt(i)?.toString().orEmpty())
                    }
                }
                is String -> if (raw.isNotEmpty()) add(raw) else Unit
                else -> Unit
            }
        }.onFailure { t -> LogBus.warn("读取文本列表失败：" + describe(t)) }
    }

    private fun doubleToInput(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "0"
        val scale = decimals.coerceIn(0, 6)
        if (scale == 0) return value.toLong().toString()
        // 固定 locale：某些地区会把小数点写成逗号，toDoubleOrNull 就读不回来了。
        return java.lang.String.format(java.util.Locale.US, "%." + scale + "f", value)
    }

    private fun normalizeHex(raw: String): String {
        val value = raw.trim().trimStart('#')
        if (value.length == 6 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return "#" + value.uppercase(java.util.Locale.US)
        }
        return ""
    }

    /** 系统解析 color int；非法值回 null，绝不把用户输入的字串当颜色资源。 */
    private fun parseHex(raw: String): Int? {
        if (raw.isEmpty()) return null
        return runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
    }

    private fun safeSectionText(raw: String?, fallback: String): String =
        if (raw.isNullOrBlank()) fallback else raw

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    /** 色块：只用系统解析出来的 int 画矩形，背景仍是 ef_panel 的切边框。 */
    private class SwatchDrawable(private val panelColor: Int) : Drawable() {
        var paintColor: Int = panelColor
            set(value) {
                field = value
                invalidateSelf()
            }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = panelColor
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val inset = stroke.strokeWidth / 2f
            fill.color = paintColor
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fill)
            canvas.drawRect(
                b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, stroke
            )
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            stroke.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            stroke.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * flush() 的落盘器：只保存值和 config 的只读快照，不持有任何 View。
     */
    private class Values(private val source: JSONObject) : Binder {
        private val target = JSONObject()

        /** 只能在 flush 时才能读到的值（例如 EfRangeInput 内部的输入框）。 */
        private val flushers = ArrayList<(JSONObject) -> Unit>()

        fun addFlusher(flusher: (JSONObject) -> Unit) {
            flushers.add(flusher)
        }

        override fun flush(): JSONObject {
            // 先把"只能延迟读"的值写进 target，再拼最终结果，保证用户最后一眼看到的数字被写回。
            flushers.forEach { runCatching { it(target) }.onFailure { t -> LogBus.warn("读取延迟值失败：" + describe(t)) } }
            val result = JSONObject()
            // 保留 spec 没渲染到的原始 key，不把用户配置悄悄丢掉。
            runCatching {
                val keys = source.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!target.has(key)) result.put(key, source.get(key))
                }
            }.onFailure { t -> LogBus.warn("复制原始配置失败：" + describe(t)) }
            runCatching {
                val keys = target.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result.put(key, target.get(key))
                }
            }.onFailure { t -> LogBus.warn("写回设置失败：" + describe(t)) }
            return result
        }

        fun put(key: String, value: Any) {
            runCatching { target.put(key, value) }
                .onFailure { t -> LogBus.warn("暂存设置失败：" + describe(t)) }
        }

        fun putRange(key: String, min: Long, max: Long) {
            // 契约第 8 节：min / max 必须是 Long，JSONObject.put 会装箱成 java.lang.Long。
            put(key, JSONObject().put("min", min).put("max", max))
        }

        private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName
    }
}