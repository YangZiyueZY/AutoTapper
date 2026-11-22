package com.example.autotapper

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.autotapper.plugin.Field
import com.example.autotapper.plugin.ScreenSpec
import com.example.autotapper.plugin.Section
import com.example.autotapper.runtime.PluginRegistry
import com.example.autotapper.ui.EfSpecRenderer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件步骤编辑器：宿主按插件的 ScreenSpec 画表单，不执行插件代码。
 *
 * spec_class 是可选的 Intent extra：给了就从已装载插件里找 editorSpec，
 * 没给（或加载失败）就用内置演示 spec，方便单独打开这个页面验收渲染效果。
 */
class PluginStepEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPEC_CLASS = "spec_class"
        const val EXTRA_PLUGIN_CONFIG = "plugin_config"

        private const val KEY_DEMO_NOTE = "note"
        private const val KEY_DEMO_DURATION = "duration"
        private const val KEY_DEMO_RATIO = "ratio"
        private const val KEY_DEMO_DELAY = "delay"
        private const val KEY_DEMO_ENABLED = "enabled"
        private const val KEY_DEMO_MODE = "mode"
        private const val KEY_DEMO_REGIONS = "regions"
        private const val KEY_DEMO_COLOR = "color"
        private const val KEY_DEMO_TEXTS = "texts"
        private const val KEY_DEMO_STEP = "step"
    }

    private var binder: EfSpecRenderer.Binder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    /** 整页用代码构建：布局只由 Ef 基元和 ef_* 颜色组成，不引入新 drawable。 */
    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ef.color(this@PluginStepEditorActivity, R.color.ef_paper))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(buildHeader())
        root.addView(Ef.matchSpacer(this, 1))

        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ef.dp(this@PluginStepEditorActivity, 20),
                Ef.dp(this@PluginStepEditorActivity, 20),
                Ef.dp(this@PluginStepEditorActivity, 20),
                Ef.dp(this@PluginStepEditorActivity, 20)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val spec = loadSpec()
        // 有 EXTRA_PLUGIN_CONFIG 就用它（编辑已有步骤时规则里传进来的真实配置），
        // 没有才退回演示用默认值——否则"编辑插件步骤"一保存就会把真实配置覆盖成演示 key。
        val config = loadConfig()
        binder = runCatching { EfSpecRenderer(this).render(fields, spec, config) }
            .onFailure { t ->
                LogBus.warn("渲染插件设置页失败：" + (t.message ?: t.javaClass.simpleName))
                Toast.makeText(
                    this,
                    R.string.plugin_spec_load_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }.getOrNull()
        scroller.addView(fields)
        root.addView(scroller)
        root.addView(buildFooter())
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ef.color(this@PluginStepEditorActivity, R.color.ef_command_bar))
        setPadding(
            Ef.dp(this@PluginStepEditorActivity, 20),
            Ef.dp(this@PluginStepEditorActivity, 18),
            Ef.dp(this@PluginStepEditorActivity, 20),
            Ef.dp(this@PluginStepEditorActivity, 18)
        )
        addView(Ef.eyebrow(this@PluginStepEditorActivity, getString(R.string.plugin_spec_eyebrow)).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        addView(Ef.heading(
            this@PluginStepEditorActivity,
            getString(R.string.plugin_spec_title),
            22f
        ).apply {
            setTextColor(Ef.color(this@PluginStepEditorActivity, R.color.ef_command_bar_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@PluginStepEditorActivity, 4) }
        })
    }

    private fun buildFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(
            Ef.dp(this@PluginStepEditorActivity, 20),
            Ef.dp(this@PluginStepEditorActivity, 12),
            Ef.dp(this@PluginStepEditorActivity, 20),
            Ef.dp(this@PluginStepEditorActivity, 12)
        )
        val cancel = EfBlock(this@PluginStepEditorActivity, getString(R.string.plugin_spec_cancel))
        val save = EfBlock(
            this@PluginStepEditorActivity,
            getString(R.string.plugin_spec_save),
            primary = true
        )
        cancel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        save.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Ef.dp(this@PluginStepEditorActivity, 10) }
        cancel.setOnClickListener { finish() }
        save.setOnClickListener { saveAndFinish() }
        addView(cancel)
        addView(save)
    }

    private fun saveAndFinish() {
        val json = runCatching { binder?.flush() ?: JSONObject() }
            .onFailure { t ->
                LogBus.warn("保存插件设置失败：" + (t.message ?: t.javaClass.simpleName))
                Toast.makeText(this, R.string.plugin_spec_load_failed, Toast.LENGTH_SHORT).show()
            }.getOrNull() ?: return
        val result = Intent().putExtra(EXTRA_PLUGIN_CONFIG, json.toString())
        setResult(RESULT_OK, result)
        Toast.makeText(this, R.string.plugin_spec_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 从已装载插件里按类名找步骤类型并取 editorSpec。
     *
     * spec_class 可以是：PluginStepType 实现类的全限定名、
     * "<pluginId>.<stepType.typeId>"、或 "<pluginId>|<typeId>"。
     * 找不到 / 插件抛异常时只记 LogBus 并回落到演示 spec，不把异常抛给界面。
     */
    private fun loadSpec(): ScreenSpec {
        val selector = intent?.getStringExtra(EXTRA_SPEC_CLASS)?.trim().orEmpty()
        if (selector.isNotEmpty()) {
            val matched = runCatching {
                PluginRegistry.allStepTypes().firstOrNull { (pluginId, _, stepType) ->
                    val cls = stepType.javaClass.name
                    cls == selector || cls.endsWith("." + selector) ||
                        selector == pluginId + "." + stepType.typeId ||
                        selector == pluginId + "|" + stepType.typeId ||
                        selector == stepType.typeId
                }
            }.onFailure { t ->
                LogBus.warn("查找插件设置页失败：" + (t.message ?: t.javaClass.simpleName))
            }.getOrNull()
            val spec = matched?.let { (_, _, stepType) ->
                runCatching { stepType.editorSpec(JSONObject()) }
                    .onFailure { t ->
                        LogBus.warn("读取插件设置页失败：" + (t.message ?: t.javaClass.simpleName))
                    }.getOrNull()
            }
            if (spec != null) return spec
            LogBus.warn("找不到插件步骤类型 " + selector + "，改用演示内容")
        }
        return demoSpec()
    }

    /** 覆盖全部 10 种字段的演示 spec，便于单开页面逐项验收。 */
    private fun demoSpec(): ScreenSpec = ScreenSpec(
        listOf(
            Section(
                "// BASICS",
                "基础设置",
                listOf(
                    Field.Text(key = KEY_DEMO_NOTE, label = "备注", placeholder = "写点什么", multiline = true),
                    Field.IntField(key = KEY_DEMO_DURATION, label = "点击时长", min = 10L, max = 2000L, unit = "ms"),
                    Field.NumberField(key = KEY_DEMO_RATIO, label = "倍率", decimals = 2, min = 0.1, max = 10.0),
                    Field.RangeField(key = KEY_DEMO_DELAY, label = "随机延迟", min = 0L, max = 5000L, unit = "ms")
                )
            ),
            Section(
                "// RULES",
                "执行规则",
                listOf(
                    Field.SwitchField(key = KEY_DEMO_ENABLED, label = "启用这一步", defaultValue = true),
                    Field.EnumField(
                        key = KEY_DEMO_MODE,
                        label = "匹配方式",
                        options = listOf("任意一个", "全部都要"),
                        values = listOf("any", "all")
                    ),
                    Field.RegionPicker(key = KEY_DEMO_REGIONS, label = "选择区域", multiple = true),
                    Field.ColorField(key = KEY_DEMO_COLOR, label = "目标颜色"),
                    Field.TextListField(key = KEY_DEMO_TEXTS, label = "识别文字", addLabel = "增加一条"),
                    Field.StepIndexField(key = KEY_DEMO_STEP, label = "跳转步骤")
                )
            )
        )
    )

    /** 演示配置：故意给一段脏 Range（纯数字），验证两种格式都能读。 */
    /** 读 Intent 里带过来的步骤配置；缺失或不是合法 JSON 时退回空对象（不抛）。 */
    private fun loadConfig(): JSONObject {
        val raw = intent?.getStringExtra(EXTRA_PLUGIN_CONFIG)?.trim().orEmpty()
        if (raw.isEmpty()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { t ->
            LogBus.warn("插件步骤配置不是合法 JSON，按空配置处理：" + (t.message ?: t.javaClass.simpleName))
            JSONObject()
        }
    }

    private fun demoConfig(): JSONObject = JSONObject()
        .put(KEY_DEMO_NOTE, "演示文本")
        .put(KEY_DEMO_DURATION, 60L)
        .put(KEY_DEMO_RATIO, 1.5)
        .put(KEY_DEMO_DELAY, 80L)
        .put(KEY_DEMO_ENABLED, true)
        .put(KEY_DEMO_MODE, "any")
        .put(KEY_DEMO_REGIONS, JSONArray().put("1"))
        .put(KEY_DEMO_COLOR, "#FF3B30")
        .put(KEY_DEMO_TEXTS, JSONArray().put("文字一"))
        .put(KEY_DEMO_STEP, 1)
}