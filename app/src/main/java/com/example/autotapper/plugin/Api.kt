package com.example.autotapper.plugin

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import org.json.JSONObject

/**
 * 插件 API —— 主程序与插件之间唯一的契约。
 *
 * 这个文件的内容必须与插件仓库 `AutoTapperPlugin/sdk/` 中的同名文件保持一致；
 * 插件编译期依赖那一份，运行时由主程序这一份提供实现。
 */
object PluginApi {
    /** 宿主支持的 API 版本。插件声明不一致就拒绝加载。 */
    const val VERSION = 1
}

/** 插件要用到的宿主能力。安装前会原样展示给用户。 */
enum class Capability {
    /** 读取屏幕截图（识图/识色/识字/数字对比） */
    SCREENSHOT,

    /** 需要联网（识字、数字对比） */
    NETWORK,

    /** 需要无障碍服务（节点查找、按键） */
    ACCESSIBILITY,

    /** 需要 Shizuku 提权（高频注入、多指） */
    SHIZUKU,

    /** 需要悬浮窗（录制遮层、自定义控制台） */
    OVERLAY
}

/** 一步的执行结果。主程序据此决定下一步（分支由 flow-control 插件解释）。 */
sealed interface StepResult {
    data class Success(val at: TapCoord? = null) : StepResult
    data class Fail(val reason: String) : StepResult
    object Stop : StepResult
    object Pause : StepResult
    data class Goto(val stepIndex: Int) : StepResult
}

// ---------------------------------------------------------------------------
// 宿主能力
// ---------------------------------------------------------------------------

/** 注入器。走哪条通道（无障碍 / Shizuku）由用户在设置里选，插件不需要关心。 */
interface ExecutorApi {
    fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit)

    fun tap(point: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) =
        tap(point.x, point.y, durationMs, onResult)

    fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit)

    /** 多点同时按下；最多 10 个点。 */
    fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit)

    /** 全局动作：返回 / 桌面。 */
    fun globalAction(action: GlobalAction, onResult: (Boolean) -> Unit)

    fun keyEvent(key: KeyCode, onResult: (Boolean) -> Unit)

    fun inputText(text: String, onResult: (Boolean) -> Unit)

    /** 当前通道实际可达的最高点击频率（次/秒），用于界面上真话标注。 */
    fun maxTapsPerSecond(): Int
}

enum class GlobalAction { BACK, HOME }

enum class KeyCode { MENU, ENTER, DEL }

/** 截图能力。授权由主程序负责；没授权时返回 null，插件按失败处理。 */
interface CaptureApi {
    fun isReady(): Boolean

    fun grabRegion(region: Region): Bitmap?

    fun grabFullscreen(): Bitmap?
}

/** 识字能力。token 在主程序设置里，插件拿不到密钥。 */
interface OcrApi {
    fun isConfigured(): Boolean

    /** 识别一张图里的文字，返回按行合并后的纯文本与每行内容。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult
}

data class OcrResult(
    val text: String,
    val lines: List<String>,
    val elapsedMs: Long,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

/** 区域存取。用户已经画好的区域，插件可以读，也可以存新的。 */
interface RegionApi {
    fun load(): List<Region>

    fun save(regions: List<Region>)

    /** 拉起打点遮层让用户画一个新区域；用户取消返回 null。 */
    suspend fun pick(label: String): Region?
}

interface LogApi {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

/** 校准过的时间。 */
interface ClockApi {
    fun nowMs(): Long

    /** 与标准时间的偏移（毫秒），主程序负责校准。 */
    fun offsetMs(): Long

    fun isBeijingTime(): Boolean
}

/**
 * 无障碍节点树。插件据此"认控件"而不是"认像素"：换分辨率、换字号也点得对。
 *
 * 为什么单独开一个能力：节点树只有无障碍服务能读到，而插件编译期只有纯 JVM 的 SDK，
 * 拿不到 android.view.accessibility 里的任何东西。宿主把它收窄成"给我一个坐标"，
 * 插件就不用（也不能）自己去摸节点。
 *
 * 约定：服务没连上时宿主给的是空实现，三个查找方法一律返回 null，插件据此走失败分支，
 * 而不是去点一个瞎猜的坐标。
 *
 * 线程：查找在宿主主线程同步完成（节点树不能跨线程读），插件在引擎协程里直接调用即可。
 */
interface NodeApi {
    /** 按视图标识找到屏幕上的可点位置。找不到返回 null，插件据此走失败分支。 */
    fun findByViewId(viewId: String, packageName: String? = null): TapCoord?

    /** 按可见文本找（exact=false 时包含匹配）。 */
    fun findByText(text: String, exact: Boolean = false): TapCoord?

    /** 按 contentDescription 找。 */
    fun findByDescription(description: String): TapCoord?

    /** 当前窗口是否可读（无障碍服务关闭时 false，插件据此提示用户）。 */
    fun isReady(): Boolean
}

/**
 * 响铃与震动。插件不关心用哪种提示音，宿主统一走「通知铃声 + 短震动」，
 * 用户可以在系统设置里改铃声，插件不需要（也不应该）知道。
 *
 * 为什么要有它：插件编译期只有一个纯 JVM 的 SDK，拿不到 android 的 RingtoneManager；
 * 而"任务完成响一声"是用户明确要的功能，所以宿主把一个最小能力暴露出来。
 */
interface HostSoundApi {

    /** 响一声，最长 [durationMs]（宿主会在到点后停掉）。 */
    fun ring(durationMs: Int = 2_000)

    /** 震动 [durationMs] 毫秒；没有振动权限时静默失败。 */
    fun vibrate(durationMs: Long = 400L)

    /** 立刻停掉正在响的铃声。 */
    fun stop()
}

/**
 * 录制能力：让用户在屏幕上自由点/滑，宿主记下来交回给插件。
 *
 * 为什么要有它：录制要一块全屏遮层 + 触摸采样，这两件事只有宿主能做
 * （插件编译期只有一个纯 JVM 的 SDK，碰不到 android.view）。
 * 插件只拿"已经录好的手势序列"，把"手势 → 步骤"的翻译留在插件里。
 *
 * 约定：没有悬浮窗权限时 [isSupported] 为 false，插件据此提示用户去授权，
 * 而不是拉起一个永远录不到东西的遮层。
 */
interface RecordApi {

    /** 没有悬浮窗权限时为 false。插件拉起录制前先问一次。 */
    fun isSupported(): Boolean

    /** 拉起录制遮层，等用户点「结束」；返回录制到的手势序列（可能为空）。取消/超时返回空表。 */
    suspend fun record(maxDurationMs: Long = 120_000L): List<RecordedGesture>
}

/**
 * 一条录到的手势。
 *
 * TAP 时 [to] 与 [from] 相同、[durationMs] 与 [holdMs] 都是按住时长；
 * SWIPE 时 [durationMs] 是滑动总时长，[holdMs] 为 0。
 */
data class RecordedGesture(
    val kind: Kind,
    val from: TapCoord,
    val to: TapCoord,
    val durationMs: Long,
    val holdMs: Long
) {
    enum class Kind { TAP, SWIPE }
}

/** 插件执行时拿到的全部能力。 */
interface PluginContext {
    val pluginId: String
    val executor: ExecutorApi
    val capture: CaptureApi
    val ocr: OcrApi
    val regions: RegionApi
    val log: LogApi
    val prefs: SharedPreferences
    val clock: ClockApi

    /** 无障碍节点树。服务没连上时是空实现，find* 一律返回 null、isReady() 为 false。 */
    val nodes: NodeApi

    /** 响铃与震动。默认实现什么都不做，宿主不支持时插件调用也不会崩。 */
    val sound: HostSoundApi

    /** 录制能力。没有悬浮窗权限时 isSupported() 为 false。 */
    val recorder: RecordApi
}

// ---------------------------------------------------------------------------
// 声明式界面：插件不画界面，只描述要哪些字段，宿主用终末地风味的部件渲染
// ---------------------------------------------------------------------------

sealed interface Field {
    val key: String
    val label: String

    data class Text(
        override val key: String,
        override val label: String,
        val placeholder: String? = null,
        val multiline: Boolean = false
    ) : Field

    data class IntField(
        override val key: String,
        override val label: String,
        val min: Long = 0,
        val max: Long = 1_000_000,
        val unit: String? = null
    ) : Field

    data class NumberField(
        override val key: String,
        override val label: String,
        val decimals: Int = 0,
        val min: Double = Double.NEGATIVE_INFINITY,
        val max: Double = Double.POSITIVE_INFINITY
    ) : Field

    /** 区间随机：min == max 表示固定值。 */
    data class RangeField(
        override val key: String,
        override val label: String,
        val min: Long = 0,
        val max: Long = 1_000_000,
        val unit: String? = null
    ) : Field

    data class SwitchField(
        override val key: String,
        override val label: String,
        val defaultValue: Boolean = false
    ) : Field

    /** options 是显示文案；values 是存盘值，长度必须与 options 一致。 */
    data class EnumField(
        override val key: String,
        override val label: String,
        val options: List<String>,
        val values: List<String>? = null
    ) : Field

    data class RegionPicker(
        override val key: String,
        override val label: String,
        val multiple: Boolean = true
    ) : Field

    data class ColorField(
        override val key: String,
        override val label: String
    ) : Field

    data class TextListField(
        override val key: String,
        override val label: String,
        val maxItems: Int = 20,
        val addLabel: String = "增加一条"
    ) : Field

    /** 引用规则里的第几步（从 1 开始显示）。 */
    data class StepIndexField(
        override val key: String,
        override val label: String
    ) : Field
}

/** 一段带眉标的分组，对应宿主界面上的一条细线分区。 */
data class Section(
    val eyebrow: String,
    val title: String,
    val fields: List<Field>
)

data class ScreenSpec(val sections: List<Section>)

// ---------------------------------------------------------------------------
// 插件入口
// ---------------------------------------------------------------------------

interface PluginStepType {
    /** 全局唯一，建议 "<pluginId>.<能力>"，例如 "tap-styles.longpress"。 */
    val typeId: String
    val displayName: String
    val requires: Set<Capability>

    fun defaultConfig(): JSONObject

    fun editorSpec(config: JSONObject): ScreenSpec

    /** 步骤列表里一行摘要；返回空串时宿主回退到 displayName。 */
    fun summarize(config: JSONObject): String = ""

    suspend fun execute(ctx: PluginContext, config: JSONObject): StepResult
}

/** 需要往宿主界面里加东西的插件（控制台格子等）用这个扩展点。 */
interface HostExtension {
    val id: String
}

/** 控制台格子扩展：图标资源名由宿主解析，插件只给标识与回调。 */
interface ConsoleCellExtension : HostExtension {
    val iconName: String
    val label: String
    fun onClick(context: PluginContext)
}

// ---------------------------------------------------------------------------
// 流程控制扩展点：让"分支与时机"也能是插件
// ---------------------------------------------------------------------------

/**
 * 策略插件：支配**全部**步骤的执行节奏与分支。
 *
 * 为什么单独开一个扩展点：延迟、超时、随机次数、成功/失败分支这些东西如果写进引擎，
 * 就只有主程序能改；如果每个步骤插件都自己实现一遍，20 个插件会有 20 套不一致的分支语义。
 * 所以把它们抽成"策略"——装了 flow-control 插件，引擎每一步前后都会问它一次。
 *
 * 没有策略插件时引擎按顺序执行，行为与升级前完全一致。
 *
 * 线程：decide() 在引擎的协程（主线程调度器）里调用，**必须立刻返回**。
 * 要等待就用 [PolicyDecision.Delay]，不要在里面挂起或睡眠。
 */
interface StepPolicyPlugin : HostExtension {
    /**
     * 宿主在插件装载后调用一次，插件把 [PluginContext] 缓存起来就能读自己的 prefs / 写日志。
     * 不建议在 decide() 里做 IO —— 那里只该读内存里的配置快照。
     */
    fun attach(context: PluginContext) {}

    fun decide(call: PolicyCall): PolicyDecision
}

enum class PolicyPhase {
    /** 步骤即将执行（还没下发注入）。 */
    BEFORE,

    /** 步骤已经跑完一次，[PolicyCall.result] 有值。 */
    AFTER
}

/** AFTER 阶段的结果快照。插件不需要认识宿主内部的 StepResult 类型。 */
enum class PolicyResult { SUCCESS, FAIL, STOP, PAUSE, GOTO }

/**
 * 一次策略询问。字段都是只读快照，插件改不动规则本身。
 *
 * @param stepIndex 0 基
 * @param stepCount 规则里的步骤总数
 * @param typeId     "core.tap" 或插件步骤的 typeId
 * @param config     该步的配置；CoreStep 会给 {"x","y","durationMs"}
 * @param attempt    同一轮里第几次执行这一步（1 基）
 * @param result     仅 AFTER 阶段有值
 * @param elapsedMs  仅 AFTER 阶段有值，本步实际耗时
 */
data class PolicyCall(
    val stepIndex: Int,
    val stepCount: Int,
    val typeId: String,
    val note: String?,
    val config: JSONObject,
    val phase: PolicyPhase,
    val attempt: Int,
    val result: PolicyResult? = null,
    val elapsedMs: Long = 0L
)

/** 策略给出的决定。引擎只解释这几种，插件不能直接操作引擎。 */
sealed interface PolicyDecision {

    /** 什么都不做，按原计划继续。 */
    object Proceed : PolicyDecision

    /** 执行前等待 ms 毫秒（随机时间也用它，插件自己算好）。 */
    data class Delay(val ms: Long) : PolicyDecision

    /** 这一步这一轮再执行 times 次（1 表示不再重复）。 */
    data class Repeat(val times: Int) : PolicyDecision

    /** 跳到第 stepIndex 步（0 基）。 */
    data class Goto(val stepIndex: Int) : PolicyDecision

    /** 停止整条规则。 */
    object Stop : PolicyDecision

    /** 暂停，等用户点继续。 */
    object Pause : PolicyDecision

    /** 覆盖这一单步的超时时间（毫秒，0 = 不限时）。 */
    data class Timeout(val ms: Long) : PolicyDecision
}

interface AutoTapperPlugin {
    val id: String
    val name: String
    val version: String
    val apiVersion: Int
    /** 宿主 versionCode 低于此值时不加载，并提示用户升级主程序。 */
    val minHostVersionCode: Int

    fun stepTypes(): List<PluginStepType>

    fun extensions(): List<HostExtension> = emptyList()
}