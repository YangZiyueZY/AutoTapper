package com.example.autotapper

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autotapper.engine.ActiveExecutor
import com.example.autotapper.engine.AutomationExecutor
import com.example.autotapper.engine.PluginStepBridgeImpl
import com.example.autotapper.engine.RuleStore
import com.example.autotapper.model.CoreStep
import com.example.autotapper.model.PluginStep
import com.example.autotapper.model.Rule
import com.example.autotapper.model.Step
import com.example.autotapper.plugin.PluginStepType
import com.example.autotapper.plugin.StepResult
import com.example.autotapper.runtime.PluginManager
import com.example.autotapper.runtime.PluginRegistry
import com.example.autotapper.ui.EfHatch
import com.example.autotapper.ui.EfPageWidth
import com.example.autotapper.ui.RuleListAdapter
import com.example.autotapper.ui.RuleRow
import com.example.autotapper.ui.StepListAdapter
import com.example.autotapper.ui.StepRow
import com.example.autotapper.ui.StepRowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 规则与步骤（// RULES / // STEPS）：插件化之后唯一还缺的闭环。
 *
 * 两屏装在同一个 Activity 里（和插件面板同一做法）：返回键先回规则列表，再退出。
 * 规则列表 →（点行，先把它设为当前规则再进去）→ 步骤列表 → 点行编辑这一步。
 *
 * 几条硬约束：
 * 1. 数据只从 [RuleStore] 来，界面不自己拼文件路径；每次增删改排序启用都先落盘再整屏重画，
 *    保证"屏上的"就是"内存里的"就是"引擎会跑的"；
 * 2. 落盘失败只 Toast，不把异常抛给界面；
 * 3. 试跑走 [ActiveExecutor]（注入器跟着服务生命周期，没有就如实说"先打开无障碍服务或 Shizuku"），
 *    插件步骤走 [PluginStepBridgeImpl]，所以插件抛异常也只会变成一条失败原因；
 * 4. 编辑器回包（[PluginStepEditorActivity]）在 isFinishing / isDestroyed 之后一律不理。
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var tvEyebrow: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnCommand: TextView
    private lateinit var listScreen: LinearLayout
    private lateinit var stepsScreen: LinearLayout
    private lateinit var rvRules: RecyclerView
    private lateinit var rvSteps: RecyclerView
    private lateinit var hatchHolder: FrameLayout
    private lateinit var stepHatchHolder: FrameLayout
    private lateinit var backHolder: LinearLayout
    private lateinit var btnTrash: TextView

    private lateinit var hatchView: EfHatch
    private lateinit var stepHatchView: EfHatch
    private lateinit var ruleAdapter: RuleListAdapter
    private lateinit var stepAdapter: StepListAdapter

    /** 当前停在步骤屏的规则；null = 停在规则列表。 */
    private var stepRuleId: String? = null

    /** 正在等编辑器回包的步骤 id；回调里靠它定位。 */
    private var editingStepId: String? = null

    /** 试跑进行中：挡住连点，避免两次注入叠在一起。 */
    private var running = false

    /** 回收站对话框；恢复 / 彻底删除之后要把它收起来重算。 */
    private var trashDialog: AlertDialog? = null

    /**
     * 落盘与试跑都在这个作用域里；界面销毁时一起取消，
     * 回调就不会在已销毁的界面上继续跑（这里没用 lifecycleScope，工程没有显式 lifecycle-runtime-ktx 依赖）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 试跑用的桥：懒建，内部只持 application context，一个插件一个上下文。 */
    private val stepBridge: PluginStepBridgeImpl by lazy {
        PluginStepBridgeImpl(
            this,
            { ActiveExecutor.current },
            { id, reason ->
                runCatching { PluginManager.get(this).disableAfterCrashes(id, reason) }
                Unit
            }
        )
    }

    /** 插件步骤编辑器回包：拿 "plugin_config" 重新解析成 config，替换那一步再落盘。 */
    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val stepId = editingStepId
        editingStepId = null
        if (stepId == null || isFinishing || isDestroyed) {
            return@registerForActivityResult
        }
        // 取消：新加的插件步骤保留 defaultConfig()，既有步骤保持原样，都不算失败
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val text = result.data?.getStringExtra(PluginStepEditorActivity.EXTRA_PLUGIN_CONFIG)
        if (text.isNullOrBlank()) {
            return@registerForActivityResult
        }
        applyPluginConfig(stepId, text)
    }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        // 平板：把两屏内容收窄到 720dp 居中，手机上无感
        EfPageWidth.cap(findViewById(R.id.list_screen), findViewById(R.id.steps_screen))

        tvEyebrow = findViewById(R.id.tv_command_eyebrow)
        tvTitle = findViewById(R.id.tv_command_title)
        btnCommand = findViewById(R.id.btn_command_action)
        listScreen = findViewById(R.id.list_screen)
        stepsScreen = findViewById(R.id.steps_screen)
        rvRules = findViewById(R.id.rv_rules)
        rvSteps = findViewById(R.id.rv_steps)
        hatchHolder = findViewById(R.id.hatch_holder)
        stepHatchHolder = findViewById(R.id.step_hatch_holder)
        backHolder = findViewById(R.id.back_holder)
        btnTrash = findViewById(R.id.btn_trash)

        stepRuleId = savedInstanceState?.getString(KEY_STEP_RULE_ID)
        editingStepId = savedInstanceState?.getString(KEY_EDITING_STEP)

        setupCommandButton()
        setupTrashBar()
        setupBackRow()
        setupScreens()

        // 返回键：步骤屏 → 规则列表，再按一次才退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stepRuleId != null) {
                    showRules()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从编辑器 / 插件面板回来时插件注册表可能已经变了（缺插件 → 有插件），整屏重算一次
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STEP_RULE_ID, stepRuleId)
        outState.putString(KEY_EDITING_STEP, editingStepId)
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        trashDialog = null
        // 适配器持有本界面传进去的回调：让 RecyclerView 先松手，别留一份旧界面的引用
        runCatching { rvRules.adapter = null }
        runCatching { rvSteps.adapter = null }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 界面骨架
    // -----------------------------------------------------------------------

    /** 右上角动作块：墨底切角，按下擦黄；文案随屏幕切换（新建 / 加一步）。 */
    private fun setupCommandButton() {
        val rest = Ef.cutBackground(this, R.color.ef_ink, R.color.ef_ink2)
        val pressed = Ef.cutBackground(this, R.color.ef_signal, null)
        btnCommand.background = rest
        btnCommand.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    btnCommand.background = pressed
                    btnCommand.setTextColor(Ef.color(this@RulesActivity, R.color.ef_on_signal))
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnCommand.background = rest
                    btnCommand.setTextColor(Ef.color(this@RulesActivity, R.color.ef_command_bar_text))
                }
            }
            // 不吃事件：点击仍然交给 OnClickListener
            false
        }
    }

    /** 动作块的文案与动作一起换，避免"换了字还留着旧监听"。 */
    private fun setCommandAction(label: String, action: () -> Unit) {
        btnCommand.text = label
        btnCommand.contentDescription = label
        btnCommand.setOnClickListener { action() }
    }

    /** 底部回收站入口：整行可点，按下落一档浅灰。 */
    private fun setupTrashBar() {
        btnTrash.background = null
        btnTrash.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    btnTrash.background = Ef.rectBackground(this@RulesActivity, R.color.ef_chip)

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    btnTrash.background = null
            }
            false
        }
        btnTrash.setOnClickListener { showTrashDialog() }
    }

    private fun setupBackRow() {
        val back = TextView(this).apply {
            text = getString(R.string.rules_back_to_rules)
            textSize = 13f
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isSingleLine = true
            minimumHeight = Ef.dp(this@RulesActivity, 48)
            setTextColor(Ef.color(this@RulesActivity, R.color.ef_ink))
            background = Ef.cutBackground(this@RulesActivity, R.color.ef_chip, null, cutDp = 6f)
            setPadding(
                Ef.dp(this@RulesActivity, 12),
                Ef.dp(this@RulesActivity, 8),
                Ef.dp(this@RulesActivity, 20),
                Ef.dp(this@RulesActivity, 8)
            )
            setOnClickListener { showRules() }
        }
        backHolder.addView(
            back,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun setupScreens() {
        hatchView = EfHatch(this, getString(R.string.rules_empty))
        hatchHolder.addView(
            hatchView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        stepHatchView = EfHatch(this, getString(R.string.rules_steps_empty))
        stepHatchHolder.addView(
            stepHatchView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        ruleAdapter = RuleListAdapter(
            onRowClick = { row -> enterRule(row.id) },
            onRowActions = { row -> showRuleActions(row) }
        )
        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = ruleAdapter

        stepAdapter = StepListAdapter(
            onRowClick = { row -> editStep(row) },
            onToggleEnabled = { row -> toggleStep(row.id) },
            onMoveUp = { row -> moveStep(row.id, -1) },
            onMoveDown = { row -> moveStep(row.id, 1) },
            onRun = { row -> runStep(row) },
            onDelete = { row -> confirmDeleteStep(row) }
        )
        rvSteps.layoutManager = LinearLayoutManager(this)
        rvSteps.adapter = stepAdapter
    }

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

    private fun render() {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching {
            if (stepRuleId != null) {
                renderSteps()
            } else {
                renderRules()
            }
        }.onFailure { LogBus.error("刷新规则界面失败：" + describe(it)) }
    }

    private fun renderRules() {
        listScreen.visibility = View.VISIBLE
        stepsScreen.visibility = View.GONE

        // 当前规则采用 RuleStore 的口径：没设过就取最近更新的一条，
        // 这样黄块标的和引擎真会跑的那条永远是同一条。
        val currentId = runCatching { RuleStore.currentRule(this)?.id }.getOrNull()
        val rows = runCatching { RuleStore.list(this) }.getOrDefault(emptyList()).mapNotNull { summary ->
            val rule = loadRule(summary.id) ?: return@mapNotNull null
            RuleRow(
                id = rule.id,
                name = rule.name,
                meta = metaText(rule),
                isCurrent = rule.id == currentId
            )
        }
        ruleAdapter.submit(rows)
        val empty = rows.isEmpty()
        rvRules.visibility = if (empty) View.GONE else View.VISIBLE
        hatchHolder.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            hatchView.setMessage(getString(R.string.rules_empty))
        }

        tvEyebrow.text = getString(R.string.rules_eyebrow_rules)
        tvTitle.text = getString(R.string.rules_title_rules)
        setCommandAction(getString(R.string.rules_action_new)) { showNewRuleDialog() }

        val trashCount = runCatching { RuleStore.trash(this).size }.getOrDefault(0)
        btnTrash.text = getString(R.string.rules_trash_entry, trashCount, RuleStore.TRASH_LIMIT)
    }

    private fun renderSteps() {
        val id = stepRuleId ?: return
        val rule = loadRule(id)
        if (rule == null) {
            // 规则在别处被删掉了：退回规则列表，别留一屏空数据
            stepRuleId = null
            renderRules()
            return
        }
        listScreen.visibility = View.GONE
        stepsScreen.visibility = View.VISIBLE

        tvEyebrow.text = getString(R.string.rules_eyebrow_steps)
        tvTitle.text = rule.name
        setCommandAction(getString(R.string.rules_action_add_step)) { showAddStepDialog() }

        val rows = rule.steps.mapIndexed { index, step ->
            buildStepRow(index, step, rule.steps.size)
        }
        stepAdapter.submit(rows)
        val empty = rows.isEmpty()
        rvSteps.visibility = if (empty) View.GONE else View.VISIBLE
        stepHatchHolder.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            stepHatchView.setMessage(getString(R.string.rules_steps_empty))
        }
    }

    private fun metaText(rule: Rule): String =
        if (rule.isInfinite) {
            getString(R.string.rules_meta_steps_infinite, rule.steps.size)
        } else {
            getString(R.string.rules_meta_steps_loop, rule.steps.size, rule.loopCount)
        }

    /**
     * 一行的数据。插件步骤的摘要优先用插件自己的 summarize(config)，
     * 空串才回退 config 里的 __summary（[Step.summary] 的口径），再回退 "pluginId / typeId"。
     */
    private fun buildStepRow(index: Int, step: Step, total: Int): StepRow {
        val number = index + 1
        val canMoveUp = index > 0
        val canMoveDown = index < total - 1
        return when (step) {
            is CoreStep -> StepRow(
                id = step.id,
                number = number,
                typeText = getString(R.string.rules_type_core),
                summary = runCatching { step.summary() }.getOrDefault(""),
                state = if (step.enabled) StepRowState.ON else StepRowState.OFF,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown
            )

            is PluginStep -> {
                val resolved = runCatching { PluginRegistry.resolveStepType(step.typeId) }.getOrNull()
                if (resolved == null) {
                    StepRow(
                        id = step.id,
                        number = number,
                        typeText = step.typeId,
                        summary = runCatching { step.summary() }.getOrDefault(""),
                        state = StepRowState.MISSING,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown
                    )
                } else {
                    val pluginName = runCatching { resolved.first.name }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: step.pluginId
                    val typeName = runCatching { resolved.second.displayName }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: step.typeId
                    val fromPlugin = runCatching { resolved.second.summarize(step.config) }
                        .getOrNull().orEmpty()
                    StepRow(
                        id = step.id,
                        number = number,
                        typeText = getString(R.string.rules_pick_plugin, pluginName, typeName),
                        summary = if (fromPlugin.isNotBlank()) {
                            fromPlugin
                        } else {
                            runCatching { step.summary() }.getOrDefault("")
                        },
                        state = if (step.enabled) StepRowState.ON else StepRowState.OFF,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 屏幕切换
    // -----------------------------------------------------------------------

    private fun showRules() {
        stepRuleId = null
        editingStepId = null
        render()
    }

    private fun enterRule(id: String) {
        // 先设为当前规则再进步骤屏：避免"看到的规则"和"会跑的规则"变成两套状态
        runCatching { RuleStore.setCurrent(this, id) }
        stepRuleId = id
        render()
    }

    // -----------------------------------------------------------------------
    // 规则：新建 / 重命名 / 复制 / 删除 / 设为当前
    // -----------------------------------------------------------------------

    private fun showRuleActions(row: RuleRow) {
        val actions = arrayOf(
            getString(R.string.rules_action_set_current),
            getString(R.string.rules_action_rename),
            getString(R.string.rules_action_copy),
            getString(R.string.rules_action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(row.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> setCurrentRule(row)
                    1 -> showRenameDialog(row)
                    2 -> copyRule(row)
                    3 -> confirmDeleteRule(row)
                }
            }
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .show()
    }

    private fun setCurrentRule(row: RuleRow) {
        val ok = runCatching { RuleStore.setCurrent(this, row.id) }.isSuccess
        if (!ok) {
            toast(getString(R.string.rules_save_failed))
            return
        }
        toast(getString(R.string.rules_set_current))
        render()
    }

    private fun showNewRuleDialog() {
        val input = textField(getString(R.string.rules_dialog_name_hint), "", numeric = false)
        val column = dialogColumn()
        addLabelledField(column, R.string.rules_dialog_name_hint, input, 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rules_dialog_new_title)
            .setView(wrapScroll(column))
            .setPositiveButton(R.string.rules_dialog_ok, null)
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                    .ifBlank { getString(R.string.rules_default_new_name) }
                dialog.dismiss()
                createRule(name)
            }
        }
        dialog.show()
    }

    private fun createRule(name: String) {
        val rule = Rule.empty(name)
        if (!persist(rule)) {
            return
        }
        // 一条规则都没有的时候，新建的这条就是当前规则（否则引擎没东西可跑）
        if (runCatching { RuleStore.currentRuleId(this) }.getOrNull() == null) {
            runCatching { RuleStore.setCurrent(this, rule.id) }
        }
        toast(getString(R.string.rules_created, name))
        render()
    }

    private fun showRenameDialog(row: RuleRow) {
        val input = textField(getString(R.string.rules_dialog_name_hint), row.name, numeric = false)
        val column = dialogColumn()
        addLabelledField(column, R.string.rules_dialog_name_hint, input, 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rules_dialog_rename_title)
            .setView(wrapScroll(column))
            .setPositiveButton(R.string.rules_dialog_ok, null)
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.rules_need_name))
                    return@setOnClickListener
                }
                dialog.dismiss()
                renameRule(row.id, name)
            }
        }
        dialog.show()
    }

    private fun renameRule(id: String, name: String) {
        val rule = loadRule(id) ?: return
        val renamed = rule.copy(name = name, updatedAt = System.currentTimeMillis())
        if (persist(renamed)) {
            toast(getString(R.string.rules_renamed))
            render()
        }
    }

    private fun copyRule(row: RuleRow) {
        val rule = loadRule(row.id) ?: return
        // 步骤 id 也换新：两条规则里的步骤必须各自唯一，否则编辑一条会串到另一条上
        val copiedSteps = rule.steps.map { step -> withId(step, UUID.randomUUID().toString()) }
        val copy = rule.copy(
            id = UUID.randomUUID().toString(),
            name = rule.name + " " + getString(R.string.rules_copy_suffix),
            steps = copiedSteps,
            updatedAt = System.currentTimeMillis()
        )
        if (persist(copy)) {
            toast(getString(R.string.rules_copied, copy.name))
            render()
        }
    }

    private fun confirmDeleteRule(row: RuleRow) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_dialog_delete_title, row.name))
            .setMessage(R.string.rules_dialog_delete_body)
            .setPositiveButton(R.string.rules_action_delete) { _, _ -> deleteRule(row.id) }
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .show()
    }

    private fun deleteRule(id: String) {
        val ok = runCatching { RuleStore.delete(this, id) }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.rules_delete_failed))
            return
        }
        // 正在看的那条被删了：退回列表，否则步骤屏会停在一个不存在的规则上
        if (stepRuleId == id) {
            stepRuleId = null
        }
        toast(getString(R.string.rules_deleted))
        render()
    }

    // -----------------------------------------------------------------------
    // 回收站
    // -----------------------------------------------------------------------

    private fun showTrashDialog() {
        if (isFinishing || isDestroyed) {
            return
        }
        val items = runCatching { RuleStore.trash(this) }.getOrDefault(emptyList())
        val column = dialogColumn()
        if (items.isEmpty()) {
            column.addView(Ef.field(this, getString(R.string.rules_trash_empty)))
            column.addView(
                Ef.field(this, getString(R.string.rules_trash_title, 0, RuleStore.TRASH_LIMIT)).apply {
                    textSize = 11f
                }
            )
        } else {
            items.forEachIndexed { index, item ->
                column.addView(buildTrashRow(item))
                if (index < items.size - 1) {
                    column.addView(
                        View(this).apply { setBackgroundColor(Ef.color(this@RulesActivity, R.color.ef_rule)) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Ef.dp(this, 1)
                        ).apply { topMargin = Ef.dp(this@RulesActivity, 6) }
                    )
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_trash_title, items.size, RuleStore.TRASH_LIMIT))
            .setView(wrapScroll(column))
            .setPositiveButton(R.string.rules_action_close, null)
            .create()
        // 恢复 / 彻底删除之后整屏重算：条目数与底部入口的文案都要跟着变
        dialog.setOnDismissListener {
            trashDialog = null
            render()
        }
        trashDialog = dialog
        dialog.show()
    }

    private fun buildTrashRow(item: RuleStore.TrashItem): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, Ef.dp(this, 6), 0, Ef.dp(this, 6))

        val nameView = TextView(this).apply {
            text = item.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isSingleLine = true
            setTextColor(Ef.color(this@RulesActivity, R.color.ef_ink))
        }
        val metaView = Ef.field(
            this,
            getString(R.string.rules_meta_steps_only, item.stepCount) + " · " +
                getString(R.string.rules_trash_deleted_at, formatTrashStamp(item.deletedAt))
        )
        metaView.textSize = 11f
        metaView.setTextColor(Ef.color(this, R.color.ef_ink2))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, Ef.dp(this, 6), 0, 0)
        val restore = textButton(getString(R.string.rules_action_restore), danger = false)
        val purge = textButton(getString(R.string.rules_action_purge), danger = true)
        restore.setOnClickListener { restoreTrashItem(item) }
        purge.setOnClickListener { confirmPurgeTrash(item) }
        buttons.addView(restore)
        buttons.addView(purge)

        row.addView(
            nameView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        row.addView(
            metaView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@RulesActivity, 2) }
        )
        row.addView(buttons)
        return row
    }

    private fun restoreTrashItem(item: RuleStore.TrashItem) {
        val ok = runCatching { RuleStore.restore(this, item) }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.rules_trash_restore_failed))
            return
        }
        toast(getString(R.string.rules_trash_restored, item.name))
        trashDialog?.dismiss()
    }

    private fun confirmPurgeTrash(item: RuleStore.TrashItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_trash_purge_title, item.name))
            .setMessage(R.string.rules_trash_purge_body)
            .setPositiveButton(R.string.rules_action_purge) { _, _ -> purgeTrashItem(item) }
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .show()
    }

    private fun purgeTrashItem(item: RuleStore.TrashItem) {
        // RuleStore 只提供"清空整个回收站"，删单条就用 TrashItem 自带的文件句柄
        val ok = runCatching { item.file.delete() }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.rules_trash_purge_failed))
            return
        }
        toast(getString(R.string.rules_trash_purged))
        trashDialog?.dismiss()
    }

    /**
     * 回收站文件名前缀是 yyyyMMddHHmmss 的时间戳（[RuleStore.trash] 就是这么解析的），
     * 它不是 epoch 毫秒，所以不能拿 java.time 去格式化，只能按位拼。
     */
    private fun formatTrashStamp(stamp: Long): String {
        val text = stamp.toString()
        if (text.length != 14) {
            return "--"
        }
        return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8) +
            " " + text.substring(8, 10) + ":" + text.substring(10, 12)
    }

    // -----------------------------------------------------------------------
    // 加一步
    // -----------------------------------------------------------------------

    private fun showAddStepDialog() {
        if (isFinishing || isDestroyed) {
            return
        }
        val pluginTypes = runCatching { PluginRegistry.allStepTypes() }.getOrDefault(emptyList())
        val labels = ArrayList<String>(pluginTypes.size + 1)
        labels.add(getString(R.string.rules_pick_core))
        pluginTypes.forEach { (_, pluginName, type) ->
            val typeName = runCatching { type.displayName }.getOrNull().orEmpty()
                .ifBlank { type.typeId }
            labels.add(getString(R.string.rules_pick_plugin, pluginName, typeName))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rules_pick_step_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    addCoreStep()
                } else {
                    val entry = pluginTypes.getOrNull(which - 1) ?: return@setItems
                    addPluginStep(entry.first, entry.second, entry.third)
                }
            }
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .show()
    }

    private fun addCoreStep() {
        showCoreStepDialog(null) { x, y, durationMs ->
            val step = CoreStep(
                id = UUID.randomUUID().toString(),
                x = x,
                y = y,
                durationMs = durationMs
            )
            appendStep(step)
        }
    }

    /** 新建插件步骤：先落盘再开编辑器，用户取消也不会丢掉刚加的那一步（保留 defaultConfig）。 */
    private fun addPluginStep(pluginId: String, pluginName: String, type: PluginStepType) {
        val id = stepRuleId ?: return
        val rule = loadRule(id) ?: return
        val config = runCatching { type.defaultConfig() }.getOrElse { t ->
            LogBus.warn("插件 " + pluginName + " 的 defaultConfig() 抛异常：" + describe(t))
            JSONObject()
        }
        val step = PluginStep(
            id = UUID.randomUUID().toString(),
            pluginId = pluginId,
            typeId = type.typeId,
            config = config
        )
        val steps = ArrayList(rule.steps)
        steps.add(step)
        if (!persist(rule.withSteps(steps))) {
            return
        }
        render()
        editingStepId = step.id
        launchEditor(type.typeId, runCatching { config.toString() }.getOrDefault("{}"))
    }

    private fun appendStep(step: Step) {
        val id = stepRuleId ?: return
        val rule = loadRule(id) ?: return
        val steps = ArrayList(rule.steps)
        steps.add(step)
        if (persist(rule.withSteps(steps))) {
            render()
        }
    }

    /** 基础点击的对话框：X / Y / 按住时长（10..2000，默认 50）。确定时先校验再建。 */
    private fun showCoreStepDialog(existing: CoreStep?, onDone: (Int, Int, Long) -> Unit) {
        if (isFinishing || isDestroyed) {
            return
        }
        val xField = textField("", if (existing != null) existing.x.toString() else "", numeric = true)
        val yField = textField("", if (existing != null) existing.y.toString() else "", numeric = true)
        val durationField = textField(
            "",
            if (existing != null) {
                existing.durationMs.toString()
            } else {
                CoreStep.DEFAULT_DURATION_MS.toString()
            },
            numeric = true
        )
        val column = dialogColumn()
        addLabelledField(column, R.string.rules_core_x, xField, 0)
        addLabelledField(column, R.string.rules_core_y, yField, 0)
        addLabelledField(
            column,
            R.string.rules_core_duration,
            durationField,
            R.string.rules_core_duration_hint
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.rules_core_new_title else R.string.rules_core_edit_title
            )
            .setView(wrapScroll(column))
            .setPositiveButton(R.string.rules_dialog_ok, null)
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val x = xField.text.toString().trim().toIntOrNull()
                val y = yField.text.toString().trim().toIntOrNull()
                if (x == null || y == null || x < 0 || y < 0) {
                    toast(getString(R.string.rules_core_invalid))
                    return@setOnClickListener
                }
                val duration = durationField.text.toString().trim().toLongOrNull()
                if (duration == null) {
                    toast(getString(R.string.rules_core_duration_invalid))
                    return@setOnClickListener
                }
                dialog.dismiss()
                onDone(x, y, duration.coerceIn(TAP_DURATION_MIN, TAP_DURATION_MAX))
            }
        }
        dialog.show()
    }

    private fun launchEditor(typeId: String, configText: String) {
        val intent = Intent(this, PluginStepEditorActivity::class.java)
            .putExtra(PluginStepEditorActivity.EXTRA_SPEC_CLASS, typeId)
            .putExtra(PluginStepEditorActivity.EXTRA_PLUGIN_CONFIG, configText)
        runCatching { editorLauncher.launch(intent) }.onFailure { t ->
            editingStepId = null
            LogBus.warn("打开插件步骤编辑器失败：" + describe(t))
            toast(getString(R.string.rules_edit_step_failed))
        }
    }

    // -----------------------------------------------------------------------
    // 步骤：编辑 / 启用 / 排序 / 删除
    // -----------------------------------------------------------------------

    private fun editStep(row: StepRow) {
        val rule = currentRule() ?: return
        val index = rule.steps.indexOfFirst { it.id == row.id }
        if (index < 0) {
            return
        }
        when (val step = rule.steps[index]) {
            is CoreStep -> showCoreStepDialog(step) { x, y, durationMs ->
                replaceStep(rule, index, step.copy(x = x, y = y, durationMs = durationMs))
            }

            is PluginStep -> {
                if (runCatching { PluginRegistry.resolveStepType(step.typeId) }.getOrNull() == null) {
                    toast(getString(R.string.rules_edit_plugin_missing))
                    return
                }
                editingStepId = step.id
                launchEditor(
                    step.typeId,
                    runCatching { step.config.toString() }.getOrDefault("{}")
                )
            }
        }
    }

    private fun applyPluginConfig(stepId: String, configText: String) {
        val rule = currentRule() ?: return
        val index = rule.steps.indexOfFirst { it.id == stepId }
        if (index < 0) {
            return
        }
        val old = rule.steps[index]
        if (old !is PluginStep) {
            return
        }
        val config = runCatching { JSONObject(configText) }.onFailure { t ->
            LogBus.warn("插件步骤配置解析失败：" + describe(t))
        }.getOrNull() ?: return
        replaceStep(rule, index, old.copy(config = config))
    }

    private fun replaceStep(rule: Rule, index: Int, step: Step) {
        val steps = ArrayList(rule.steps)
        if (index !in steps.indices) {
            return
        }
        steps[index] = step
        if (persist(rule.withSteps(steps))) {
            toast(getString(R.string.rules_step_updated))
            render()
        }
    }

    private fun toggleStep(stepId: String) {
        val rule = currentRule() ?: return
        val index = rule.steps.indexOfFirst { it.id == stepId }
        if (index < 0) {
            return
        }
        val old = rule.steps[index]
        val steps = ArrayList(rule.steps)
        steps[index] = withEnabled(old, !old.enabled)
        if (persist(rule.withSteps(steps))) {
            render()
        }
    }

    private fun moveStep(stepId: String, delta: Int) {
        val rule = currentRule() ?: return
        val steps = ArrayList(rule.steps)
        val from = steps.indexOfFirst { it.id == stepId }
        if (from < 0) {
            return
        }
        val to = from + delta
        if (to !in steps.indices) {
            return
        }
        val moved = steps.removeAt(from)
        steps.add(to, moved)
        if (persist(rule.withSteps(steps))) {
            render()
        }
    }

    private fun confirmDeleteStep(row: StepRow) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rules_step_delete_title, row.number))
            .setMessage(R.string.rules_step_delete_body)
            .setPositiveButton(R.string.rules_action_delete) { _, _ -> deleteStep(row.id) }
            .setNegativeButton(R.string.rules_dialog_cancel, null)
            .show()
    }

    private fun deleteStep(stepId: String) {
        val rule = currentRule() ?: return
        val steps = ArrayList(rule.steps)
        val removed = steps.removeAll { step -> step.id == stepId }
        if (!removed) {
            return
        }
        if (persist(rule.withSteps(steps))) {
            toast(getString(R.string.rules_step_deleted))
            render()
        }
    }

    // -----------------------------------------------------------------------
    // 试跑：只跑这一步一次
    // -----------------------------------------------------------------------

    private fun runStep(row: StepRow) {
        val rule = currentRule() ?: return
        val index = rule.steps.indexOfFirst { it.id == row.id }
        if (index < 0) {
            return
        }
        runStepAt(index, rule.steps[index])
    }

    private fun runStepAt(index: Int, step: Step) {
        if (isFinishing || isDestroyed) {
            return
        }
        val executor = ActiveExecutor.current
        if (executor == null || !runCatching { executor.isReady() }.getOrDefault(false)) {
            toast(getString(R.string.rules_run_need_channel))
            return
        }
        if (running) {
            return
        }
        running = true
        scope.launch {
            val reason: String? = try {
                executeOnce(executor, step)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                describe(t)
            } finally {
                running = false
            }
            if (isFinishing || isDestroyed) {
                return@launch
            }
            val label = getString(R.string.rules_step_run_label, index + 1)
            if (reason == null) {
                val text = getString(R.string.rules_run_success, index + 1)
                LogBus.result(text, index, label)
                toast(text)
            } else {
                val text = getString(R.string.rules_run_failed, index + 1, reason)
                LogBus.result(text, index, label)
                toast(text)
            }
        }
    }

    private suspend fun executeOnce(executor: AutomationExecutor, step: Step): String? = when (step) {
        is CoreStep -> tapOnce(executor, step)
        is PluginStep -> executePluginStep(step)
    }

    /**
     * 基础点击包成挂起：注入器的回调可能永远不来（服务被系统掐掉），
     * 所以必须带超时，超时如实报"5 秒没有回应"，不假装成功。
     */
    private suspend fun tapOnce(executor: AutomationExecutor, step: CoreStep): String? {
        val ok = withTimeoutOrNull(CORE_TAP_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val settled = AtomicBoolean(false)
                val callback: (Boolean) -> Unit = { success ->
                    if (settled.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(success)
                    }
                }
                try {
                    executor.tap(step.x, step.y, step.durationMs, callback)
                } catch (t: Throwable) {
                    LogBus.warn("试跑点击失败：" + describe(t))
                    callback(false)
                }
            }
        }
        return when (ok) {
            null -> getString(R.string.rules_run_timeout)
            true -> null
            else -> getString(R.string.rules_run_channel_refused)
        }
    }

    private suspend fun executePluginStep(step: PluginStep): String? {
        val result = stepBridge.execute(step) ?: return getString(R.string.rules_run_plugin_missing)
        return when (result) {
            is StepResult.Success -> null
            is StepResult.Fail -> result.reason
            is StepResult.Stop -> getString(R.string.rules_run_stop)
            is StepResult.Pause -> getString(R.string.rules_run_pause)
            is StepResult.Goto -> null
        }
    }

    // -----------------------------------------------------------------------
    // 数据
    // -----------------------------------------------------------------------

    private fun loadRule(id: String): Rule? = runCatching { RuleStore.load(this, id) }.getOrNull()

    private fun currentRule(): Rule? {
        val id = stepRuleId ?: return null
        return loadRule(id)
    }

    /** 落盘；失败只 Toast 并回 false，调用方不再往下走。 */
    private fun persist(rule: Rule): Boolean {
        val ok = runCatching { RuleStore.save(this, rule) }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.rules_save_failed))
        }
        return ok
    }

    private fun withEnabled(step: Step, enabled: Boolean): Step = when (step) {
        is CoreStep -> step.copy(enabled = enabled)
        is PluginStep -> step.copy(enabled = enabled)
    }

    private fun withId(step: Step, id: String): Step = when (step) {
        is CoreStep -> step.copy(id = id)
        is PluginStep -> step.copy(id = id)
    }

    // -----------------------------------------------------------------------
    // 对话框零件
    // -----------------------------------------------------------------------

    private fun dialogColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            Ef.dp(this@RulesActivity, 20),
            Ef.dp(this@RulesActivity, 12),
            Ef.dp(this@RulesActivity, 20),
            Ef.dp(this@RulesActivity, 4)
        )
    }

    private fun wrapScroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = false
        addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addLabelledField(
        column: LinearLayout,
        labelRes: Int,
        field: EditText,
        hintRes: Int
    ) {
        column.addView(
            labelView(getString(labelRes)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@RulesActivity, 10) }
        )
        column.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@RulesActivity, 4) }
        )
        if (hintRes != 0) {
            column.addView(
                Ef.field(this, getString(hintRes)).apply { textSize = 11f },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ef.dp(this@RulesActivity, 4) }
            )
        }
    }

    private fun labelView(text: String): TextView = Ef.field(this, text).apply {
        setTextColor(Ef.color(this@RulesActivity, R.color.ef_ink2))
    }

    private fun textField(hintText: String, initial: String, numeric: Boolean): EditText =
        EditText(this).apply {
            setText(initial)
            hint = hintText
            textSize = 15f
            includeFontPadding = false
            setTextColor(Ef.color(this@RulesActivity, R.color.ef_ink))
            setHintTextColor(Ef.color(this@RulesActivity, R.color.ef_ink2))
            setBackgroundResource(R.drawable.ef_edit_box)
            setPadding(
                Ef.dp(this@RulesActivity, 12),
                Ef.dp(this@RulesActivity, 10),
                Ef.dp(this@RulesActivity, 12),
                Ef.dp(this@RulesActivity, 10)
            )
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    /** 对话框里的文字动作按钮：切角小块、48dp 触控区；红色只给删除类动作。 */
    private fun textButton(label: String, danger: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = Ef.dp(this@RulesActivity, 48)
        minimumWidth = Ef.dp(this@RulesActivity, 48)
        setPadding(
            Ef.dp(this@RulesActivity, 10),
            Ef.dp(this@RulesActivity, 8),
            Ef.dp(this@RulesActivity, 14),
            Ef.dp(this@RulesActivity, 8)
        )
        background = Ef.cutBackground(this@RulesActivity, R.color.ef_chip, null, cutDp = 6f)
        setTextColor(Ef.color(this@RulesActivity, if (danger) R.color.ef_danger else R.color.ef_ink))
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = Ef.dp(this@RulesActivity, 8) }
        Ef.applyWide(this, weight = 600, width = 118)
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    companion object {

        private const val KEY_STEP_RULE_ID = "step_rule_id"
        private const val KEY_EDITING_STEP = "editing_step_id"
        private const val CORE_TAP_TIMEOUT_MS = 5_000L
        private const val TAP_DURATION_MIN = 10L
        private const val TAP_DURATION_MAX = 2_000L
    }
}
