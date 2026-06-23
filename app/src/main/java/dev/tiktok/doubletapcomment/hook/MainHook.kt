package dev.tiktok.doubletapcomment.hook

import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

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
            if (hookCommentAbilityLifecycle()) count++
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

        private fun hookCommentAbilityLifecycle(): Boolean {
            val commentClass = findClass("com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentAssem")
                ?: return false.also { log("VideoCommentAssem not found") }

            var installed = false
            installed = hookAfter(commentClass, "onParentSet") {
                commentRegistry.register(it.thisObject, cl)
            } || installed

            installed = hookAfter(commentClass, "onViewCreated", android.view.View::class.java) {
                commentRegistry.register(it.thisObject, cl)
            } || installed

            return installed
        }

        private fun openCommentPanel(diggComponent: Any): Boolean {
            val direct = TikTokReflect.findAbilityFromOwner(
                owner = diggComponent,
                abilityClassName = COMMENT_ABILITY_CLASS,
                cl = cl
            )
            if (invokeKb0(direct, "direct")) return true

            val cached = commentRegistry.findForOwner(diggComponent, cl)
            if (invokeKb0(cached, "cached-scope")) return true

            val latest = commentRegistry.latest()
            return invokeKb0(latest, "cached-latest")
        }

        private fun invokeKb0(ability: Any?, source: String): Boolean {
            if (ability == null) return false
            return runCatching {
                XposedHelpers.callMethod(ability, "Kb0")
                log("opened comment panel via $source")
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
        private val byScope = WeakHashMap<Any, WeakReference<Any>>()
        private var latest = WeakReference<Any>(null)

        @Synchronized
        fun register(commentAssem: Any, cl: ClassLoader) {
            if (!TikTokReflect.isInstance(commentAssem, COMMENT_ABILITY_CLASS, cl)) return
            latest = WeakReference(commentAssem)

            val scope = TikTokReflect.scopeFor(commentAssem, cl)
            if (scope != null) {
                byScope[scope] = WeakReference(commentAssem)
                log("registered comment ability for scope ${scope.javaClass.name}")
            } else {
                log("registered latest comment ability without scope")
            }
        }

        @Synchronized
        fun findForOwner(owner: Any, cl: ClassLoader): Any? {
            val scope = TikTokReflect.scopeFor(owner, cl) ?: return null
            return byScope[scope]?.get()
        }

        @Synchronized
        fun latest(): Any? = latest.get()
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

        fun isInstance(value: Any, className: String, cl: ClassLoader): Boolean {
            val clazz = findClass(className, cl) ?: return false
            return clazz.isInstance(value)
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
    }
}
