package dev.tiktok.doubletapcomment.hook

import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        log("loading in ${lpparam.packageName}")
        val installed = TikTokHooks(lpparam.classLoader).install()
        log("installed hooks: $installed")
    }

    private class TikTokHooks(private val cl: ClassLoader) {
        private val commentRegistry = CommentAbilityRegistry()

        fun install(): Int {
            var count = 0
            if (hookDiggDoubleTap()) count++
            if (hookCommentAbilityBinding()) count++
            if (hookGestureDiagnostics()) count++
            return count
        }

        private fun hookDiggDoubleTap(): Boolean {
            val diggClass = findClass("com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent")
                ?: return false.also { log("DiggPanelComponent not found") }

            return runCatching {
                XposedHelpers.findAndHookMethod(
                    diggClass,
                    "handleDoubleClick",
                    MotionEvent::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            val handled = openCommentPanel(param.thisObject)
                            if (!handled) {
                                log("double tap swallowed; comment ability unavailable")
                            }
                            return null
                        }
                    }
                )
            }.onFailure {
                log("failed to hook DiggPanelComponent.handleDoubleClick: ${it.message}", it)
            }.isSuccess
        }

        private fun hookCommentAbilityBinding(): Boolean {
            val commentClass = findClass("com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentAssem")
                ?: return false.also { log("VideoCommentAssem not found") }

            var installed = false
            installed = hookAllAfter(commentClass, "Nq") {
                commentRegistry.registerBoundComment(it.thisObject, it.args.firstOrNull(), cl)
            } || installed
            installed = hookAllAfter(commentClass, "Xq") {
                commentRegistry.registerBoundComment(it.thisObject, it.args.firstOrNull(), cl)
            } || installed
            installed = hookAfter(commentClass, "onParentSet") {
                commentRegistry.registerCurrentBinding(it.thisObject, cl)
            } || installed
            installed = hookAfter(commentClass, "onViewCreated", android.view.View::class.java) {
                commentRegistry.registerCurrentBinding(it.thisObject, cl)
            } || installed

            return installed
        }

        private fun openCommentPanel(diggComponent: Any): Boolean {
            val currentAid = TikTokReflect.currentAwemeAidFromDigg(diggComponent) ?: run {
                log("double tap swallowed; current aweme aid unavailable")
                return false
            }

            val byAid = commentRegistry.findByAid(currentAid)
            if (byAid == null) {
                log("double tap has no ability for aid=${shortAid(currentAid)} registry=${commentRegistry.snapshot()}")
            }
            return invokeCommentOpenIfMatches(byAid, currentAid)
        }

        private fun hookGestureDiagnostics(): Boolean {
            if (!DIAGNOSTIC_HOOKS) return false
            var installed = false
            installed = hookBefore(View::class.java, "performLongClick") {
                val view = it.thisObject as? View
                log("diagnostic performLongClick view=${describeView(view)} stack=${shortStack()}")
            } || installed
            installed = hookBefore(Dialog::class.java, "show") {
                val dialog = it.thisObject as? Dialog
                log("diagnostic Dialog.show class=${dialog?.javaClass?.name} stack=${shortStack()}")
            } || installed
            installed = hookBefore(
                PopupWindow::class.java,
                "showAtLocation",
                View::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ) {
                val popup = it.thisObject as? PopupWindow
                log("diagnostic PopupWindow.showAtLocation class=${popup?.javaClass?.name} stack=${shortStack()}")
            } || installed
            installed = hookBefore(
                PopupWindow::class.java,
                "showAsDropDown",
                View::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ) {
                val popup = it.thisObject as? PopupWindow
                log("diagnostic PopupWindow.showAsDropDown class=${popup?.javaClass?.name} stack=${shortStack()}")
            } || installed
            return installed
        }

        private fun invokeCommentOpenIfMatches(ability: Any?, expectedAid: String): Boolean {
            if (ability == null) return false
            val actualAid = TikTokReflect.boundAwemeAidFromCommentAbility(ability)
            if (actualAid != expectedAid) {
                log(
                    "blocked mismatched comment ability; " +
                        "expected=${shortAid(expectedAid)} actual=${shortAid(actualAid)}"
                )
                return false
            }
            var lastFailure: Throwable? = null
            for (method in COMMENT_OPEN_METHODS) {
                runCatching {
                    XposedHelpers.callMethod(ability, method)
                    log("opened comment panel via $method aid=${shortAid(expectedAid)}")
                    return true
                }.onFailure {
                    lastFailure = it
                }
            }
            log("failed to invoke comment open methods $COMMENT_OPEN_METHODS: ${lastFailure?.message}")
            return false
        }

        private fun hookAfter(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Any,
            after: (XC_MethodHook.MethodHookParam) -> Unit
        ): Boolean {
            return runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    *parameterTypes,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            after(param)
                        }
                    }
                )
            }.onFailure {
                log("failed to hook ${clazz.name}.$methodName: ${it.message}", it)
            }.isSuccess
        }

        private fun hookBefore(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Any,
            before: (XC_MethodHook.MethodHookParam) -> Unit
        ): Boolean {
            return runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    *parameterTypes,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            before(param)
                        }
                    }
                )
            }.onFailure {
                log("failed to hook ${clazz.name}.$methodName: ${it.message}", it)
            }.isSuccess
        }

        private fun hookAllAfter(
            clazz: Class<*>,
            methodName: String,
            after: (XC_MethodHook.MethodHookParam) -> Unit
        ): Boolean {
            return runCatching {
                XposedBridge.hookAllMethods(
                    clazz,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            after(param)
                        }
                    }
                ).isNotEmpty()
            }.onFailure {
                log("failed to hook all ${clazz.name}.$methodName: ${it.message}", it)
            }.getOrDefault(false)
        }

        private fun findClass(name: String): Class<*>? {
            return runCatching { XposedHelpers.findClassIfExists(name, cl) }.getOrNull()
        }
    }

    private class CommentAbilityRegistry {
        private val byAid = LinkedHashMap<String, WeakReference<Any>>()

        @Synchronized
        fun registerBoundComment(commentAssem: Any, videoItemParams: Any?, cl: ClassLoader) {
            if (!TikTokReflect.isInstance(commentAssem, COMMENT_ABILITY_CLASS, cl)) return
            val aid = TikTokReflect.aidFromVideoItemParams(videoItemParams)
                ?: TikTokReflect.boundAwemeAidFromCommentAbility(commentAssem)
                ?: return
            register(commentAssem, aid)
        }

        @Synchronized
        fun registerCurrentBinding(commentAssem: Any, cl: ClassLoader) {
            if (!TikTokReflect.isInstance(commentAssem, COMMENT_ABILITY_CLASS, cl)) return
            val aid = TikTokReflect.boundAwemeAidFromCommentAbility(commentAssem) ?: return
            register(commentAssem, aid)
        }

        private fun register(commentAssem: Any, aid: String) {
            byAid[aid] = WeakReference(commentAssem)
            trimAidCache()
            log("registered comment ability aid=${shortAid(aid)}")
        }

        @Synchronized
        fun findByAid(aid: String): Any? = byAid[aid]?.get()

        @Synchronized
        fun snapshot(): String {
            return byAid.entries.joinToString(prefix = "[", postfix = "]") { (aid, ref) ->
                "${shortAid(aid)}:${if (ref.get() == null) "cleared" else "alive"}"
            }
        }

        private fun trimAidCache() {
            if (byAid.size <= MAX_AID_CACHE_SIZE) return
            val iterator = byAid.entries.iterator()
            while (byAid.size > MAX_AID_CACHE_SIZE && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private object TikTokReflect {
        fun currentAwemeAidFromDigg(diggComponent: Any): String? {
            val viewPagerAbility = firstMethodResult(diggComponent, "Qb", "zb") ?: return null

            firstAwemeAidFrom(
                viewPagerAbility,
                "LJIIIIZZ",
                "LLLLLLIL",
                "l41"
            )?.let { return it }

            firstMethodResult(viewPagerAbility, "LJLIL", "BR", "LJLIIL")?.let { currentCell ->
                aidFromAwemeLike(currentCell)?.let { return it }
            }

            return null
        }

        fun boundAwemeAidFromCommentAbility(commentAbility: Any?): String? {
            if (commentAbility == null) return null
            val reusedScope = getObjectField(commentAbility, "LLJI") ?: return null
            val videoItemParams = getObjectField(reusedScope, "LL")
            return aidFromVideoItemParams(videoItemParams)
        }

        fun aidFromVideoItemParams(videoItemParams: Any?): String? {
            if (videoItemParams == null) return null
            val aweme = callMethod(videoItemParams, "getAweme") ?: return null
            return aidFromAweme(aweme)
        }

        fun isInstance(value: Any, className: String, cl: ClassLoader): Boolean {
            val clazz = findClass(className, cl) ?: return false
            return clazz.isInstance(value)
        }

        private fun firstAwemeAidFrom(instance: Any?, vararg methods: String): String? {
            if (instance == null) return null
            for (method in methods) {
                val aid = aidFromAwemeLike(callMethodQuiet(instance, method))
                if (aid != null) return aid
            }
            return null
        }

        private fun firstMethodResult(instance: Any?, vararg methods: String): Any? {
            if (instance == null) return null
            for (method in methods) {
                val result = callMethodQuiet(instance, method)
                if (result != null) return result
            }
            log("none of ${methods.joinToString(prefix = "[", postfix = "]")} worked on ${instance.javaClass.name}")
            return null
        }

        private fun aidFromAwemeLike(value: Any?): String? {
            if (value == null) return null
            aidFromAweme(value)?.let { return it }
            val aweme = callMethodQuiet(value, "getAweme")
                ?: callMethodQuiet(callMethodQuiet(value, "getItem"), "getAweme")
                ?: return null
            return aidFromAweme(aweme)
        }

        private fun aidFromAweme(aweme: Any?): String? {
            return callMethodQuiet(aweme, "getAid") as? String
        }

        private fun callMethod(instance: Any?, method: String, vararg args: Any?): Any? {
            if (instance == null) return null
            return runCatching {
                XposedHelpers.callMethod(instance, method, *args)
            }.onFailure {
                log("reflect call failed: ${instance.javaClass.name}.$method: ${it.message}")
            }.getOrNull()
        }

        private fun callMethodQuiet(instance: Any?, method: String, vararg args: Any?): Any? {
            if (instance == null) return null
            return runCatching {
                XposedHelpers.callMethod(instance, method, *args)
            }.getOrNull()
        }

        private fun getObjectField(instance: Any?, field: String): Any? {
            if (instance == null) return null
            return runCatching {
                XposedHelpers.getObjectField(instance, field)
            }.onFailure {
                log("reflect field failed: ${instance.javaClass.name}.$field: ${it.message}")
            }.getOrNull()
        }

        private fun findClass(name: String, cl: ClassLoader): Class<*>? {
            return runCatching { XposedHelpers.findClassIfExists(name, cl) }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "DoubleTapComment"
        private const val DIAGNOSTIC_HOOKS = false
        private const val MAX_AID_CACHE_SIZE = 12
        private const val COMMENT_ABILITY_CLASS =
            "com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility"
        private val COMMENT_OPEN_METHODS = listOf("Kb0", "jc0")

        private val TARGET_PACKAGES = setOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go"
        )

        fun log(message: String, throwable: Throwable? = null) {
            XposedBridge.log("$TAG: $message")
            if (throwable != null) {
                XposedBridge.log(throwable)
            }
        }

        private fun shortAid(aid: String?): String {
            if (aid.isNullOrBlank()) return "null"
            return "#${aid.hashCode().toUInt().toString(16)}"
        }

        private fun describeView(view: View?): String {
            if (view == null) return "null"
            val idName = runCatching {
                if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
            }.getOrDefault("")
            return "${view.javaClass.name} id=$idName clickable=${view.isClickable} longClickable=${view.isLongClickable}"
        }

        private fun shortStack(): String {
            return Throwable().stackTrace
                .drop(2)
                .take(10)
                .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        }
    }
}
