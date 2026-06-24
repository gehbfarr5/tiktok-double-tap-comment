package dev.tiktok.doubletapcomment.hook

import android.view.MotionEvent
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
            return invokeKb0IfMatches(byAid, currentAid)
        }

        private fun invokeKb0IfMatches(ability: Any?, expectedAid: String): Boolean {
            if (ability == null) return false
            val actualAid = TikTokReflect.boundAwemeAidFromCommentAbility(ability)
            if (actualAid != expectedAid) {
                log(
                    "blocked mismatched comment ability; " +
                        "expected=${shortAid(expectedAid)} actual=${shortAid(actualAid)}"
                )
                return false
            }
            return runCatching {
                XposedHelpers.callMethod(ability, "Kb0")
                log("opened comment panel via aid-cache aid=${shortAid(expectedAid)}")
                true
            }.getOrElse {
                log("failed to invoke IVideoCommentAbility.Kb0: ${it.message}", it)
                false
            }
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
            val viewPagerAbility = callMethod(diggComponent, "zb") ?: return null
            val currentCell = callMethod(viewPagerAbility, "BR")
                ?: callMethod(viewPagerAbility, "LJLIIL")
                ?: return null
            val aweme = callMethod(currentCell, "getAweme") ?: return null
            return aidFromAweme(aweme)
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

        private fun aidFromAweme(aweme: Any?): String? {
            return callMethod(aweme, "getAid") as? String
        }

        private fun callMethod(instance: Any?, method: String, vararg args: Any?): Any? {
            if (instance == null) return null
            return runCatching {
                XposedHelpers.callMethod(instance, method, *args)
            }.onFailure {
                log("reflect call failed: ${instance.javaClass.name}.$method: ${it.message}")
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
        private const val MAX_AID_CACHE_SIZE = 12
        private const val COMMENT_ABILITY_CLASS =
            "com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility"

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
    }
}
