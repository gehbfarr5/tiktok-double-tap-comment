package dev.tiktok.doubletapcomment.hook

import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap
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
            val videoItemParamsClass = findClass("com.ss.android.ugc.aweme.feed.model.VideoItemParams")
                ?: return false.also { log("VideoItemParams not found") }

            var installed = false
            installed = hookAfter(commentClass, "Nq", videoItemParamsClass) {
                commentRegistry.registerBoundComment(it.thisObject, it.args.firstOrNull(), cl)
            } || installed

            return installed
        }

        private fun openCommentPanel(diggComponent: Any): Boolean {
            val currentAid = TikTokReflect.currentAwemeAidFromDigg(diggComponent) ?: run {
                log("double tap swallowed; current aweme aid unavailable")
                return false
            }

            val byAid = commentRegistry.findByAid(currentAid)
            if (invokeKb0IfMatches(byAid, currentAid, "aid-cache")) return true

            val direct = TikTokReflect.findAbilityFromOwner(
                owner = diggComponent,
                abilityClassName = COMMENT_ABILITY_CLASS,
                cl = cl
            )
            if (invokeKb0IfMatches(direct, currentAid, "direct-scope")) return true

            val cached = commentRegistry.findForOwner(diggComponent, cl)
            return invokeKb0IfMatches(cached, currentAid, "cached-scope")
        }

        private fun invokeKb0IfMatches(ability: Any?, expectedAid: String, source: String): Boolean {
            if (ability == null) return false
            val actualAid = TikTokReflect.boundAwemeAidFromCommentAbility(ability)
            if (actualAid != expectedAid) {
                log(
                    "blocked mismatched comment ability via $source; " +
                        "expected=${shortAid(expectedAid)} actual=${shortAid(actualAid)}"
                )
                return false
            }
            return runCatching {
                XposedHelpers.callMethod(ability, "Kb0")
                log("opened comment panel via $source aid=${shortAid(expectedAid)}")
                true
            }.getOrElse {
                log("failed to invoke IVideoCommentAbility.Kb0 via $source: ${it.message}", it)
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

        private fun findClass(name: String): Class<*>? {
            return runCatching { XposedHelpers.findClassIfExists(name, cl) }.getOrNull()
        }
    }

    private class CommentAbilityRegistry {
        private val byAid = LinkedHashMap<String, WeakReference<Any>>()
        private val byScope = WeakHashMap<Any, WeakReference<Any>>()

        @Synchronized
        fun registerBoundComment(commentAssem: Any, videoItemParams: Any?, cl: ClassLoader) {
            if (!TikTokReflect.isInstance(commentAssem, COMMENT_ABILITY_CLASS, cl)) return
            val aid = TikTokReflect.aidFromVideoItemParams(videoItemParams) ?: return
            byAid[aid] = WeakReference(commentAssem)
            trimAidCache()

            val scope = TikTokReflect.scopeFor(commentAssem, cl)
            if (scope != null) {
                byScope[scope] = WeakReference(commentAssem)
                log("registered comment ability aid=${shortAid(aid)} scope=${scope.javaClass.name}")
            } else {
                log("registered comment ability aid=${shortAid(aid)} without scope")
            }
        }

        @Synchronized
        fun findByAid(aid: String): Any? = byAid[aid]?.get()

        @Synchronized
        fun findForOwner(owner: Any, cl: ClassLoader): Any? {
            val scope = TikTokReflect.scopeFor(owner, cl) ?: return null
            return byScope[scope]?.get()
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
        fun findAbilityFromOwner(owner: Any, abilityClassName: String, cl: ClassLoader): Any? {
            val abilityClass = findClass(abilityClassName, cl) ?: return null
            val scope = scopeFor(owner, cl) ?: owner
            return callStatic("X.C1268304yd", cl, "LIZ", scope, abilityClass, null)
        }

        fun scopeFor(owner: Any, cl: ClassLoader): Any? {
            return callStatic("X.C1268304yd", cl, "LJIIL", owner)
        }

        fun currentAwemeAidFromDigg(diggComponent: Any): String? {
            val viewPagerAbility = callMethod(diggComponent, "zb") ?: return null
            val aweme = callMethod(viewPagerAbility, "LJIIIIZZ") ?: return null
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

        private fun callStatic(className: String, cl: ClassLoader, method: String, vararg args: Any?): Any? {
            return runCatching {
                val clazz = XposedHelpers.findClass(className, cl)
                XposedHelpers.callStaticMethod(clazz, method, *args)
            }.onFailure {
                log("reflect call failed: $className.$method: ${it.message}")
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
