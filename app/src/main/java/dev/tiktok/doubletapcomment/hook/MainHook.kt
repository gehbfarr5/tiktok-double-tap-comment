package dev.tiktok.doubletapcomment.hook

import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

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
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (openCommentPanel(param.thisObject)) {
                                param.result = null
                            } else {
                                log("double tap not consumed; falling through to TikTok default")
                            }
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
            var bindInstalled = false
            for (methodName in COMMENT_BIND_METHODS) {
                if (hookAllAfter(commentClass, methodName) {
                        commentRegistry.registerBoundComment(it.thisObject, it.args.firstOrNull(), cl)
                    }
                ) {
                    log("resolved comment bind method via fastpath name=$methodName")
                    bindInstalled = true
                    break
                }
            }
            if (!bindInstalled) {
                val videoItemParamsClass = findClass(VIDEO_ITEM_PARAMS_CLASS)
                val signatureNames = commentClass.declaredMethods
                    .filter { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.size == 1 &&
                            if (videoItemParamsClass != null) {
                                method.parameterTypes[0] == videoItemParamsClass
                            } else {
                                method.parameterTypes[0].simpleName == "VideoItemParams"
                            }
                    }
                    .map { it.name }
                    .distinct()
                for (methodName in signatureNames) {
                    if (hookAllAfter(commentClass, methodName) {
                            commentRegistry.registerBoundComment(it.thisObject, it.args.firstOrNull(), cl)
                        }
                    ) {
                        log("resolved comment bind method via signature name=$methodName")
                        bindInstalled = true
                    }
                }
            }
            if (!bindInstalled) {
                log("FAILED to resolve comment bind method")
            }
            installed = bindInstalled || installed
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
                log("double tap current aweme aid unavailable")
                return false
            }

            // Fast path: ability registered under this aid. Fallback: TikTok recycles a
            // small pool of comment-assem instances, and on scroll it rebinds an existing
            // instance to a new aweme WITHOUT re-invoking the bind method, so the registry key
            // goes stale while the live binding tracks the current video. Scan the
            // pooled abilities by their LIVE bound aid to find the current cell's ability.
            val ability = commentRegistry.findByAid(currentAid)
                ?: commentRegistry.findByLiveAid(currentAid)
            if (ability == null) {
                log("double tap has no ability for aid=${shortAid(currentAid)} registry=${commentRegistry.snapshot()}")
            }
            return invokeCommentOpenIfMatches(ability, currentAid)
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

            val fastMethod = COMMENT_OPEN_METHODS.firstOrNull { methodName ->
                ability.javaClass.methods.any { it.name == methodName && it.parameterTypes.isEmpty() }
            }
            if (fastMethod != null) {
                log("resolved comment open method via fastpath name=$fastMethod")
                return runCatching {
                    XposedHelpers.callMethod(ability, fastMethod)
                    log("opened comment panel via $fastMethod aid=${shortAid(expectedAid)}")
                    true
                }.onFailure {
                    log("failed to invoke comment open method $fastMethod: ${it.message}", it)
                }.getOrDefault(false)
            }

            val abilityInterface = findClass(COMMENT_ABILITY_CLASS)
            val signatureMethods = abilityInterface?.declaredMethods
                ?.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType == Void.TYPE
                }
                .orEmpty()
            if (signatureMethods.isEmpty()) {
                log("FAILED to resolve comment open method")
                return false
            }

            var lastFailure: Throwable? = null
            for (method in signatureMethods) {
                log("resolved comment open method via signature name=${method.name}")
                runCatching {
                    XposedHelpers.callMethod(ability, method.name)
                    log("opened comment panel via ${method.name} aid=${shortAid(expectedAid)}")
                    return true
                }.onFailure {
                    lastFailure = it
                }
            }
            log("failed to invoke signature comment open methods: ${lastFailure?.message}", lastFailure)
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

        // Recycled-cell resolution: return the pooled comment ability whose LIVE bound aid
        // (read fresh from the resolved binding fields each call) matches, regardless of its stale key.
        @Synchronized
        fun findByLiveAid(aid: String): Any? {
            for (ref in byAid.values) {
                val ability = ref.get() ?: continue
                if (TikTokReflect.boundAwemeAidFromCommentAbility(ability) == aid) {
                    return ability
                }
            }
            return null
        }

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
        private val boundParamsFieldByClass = mutableMapOf<Class<*>, String>()
        private val failedBoundParamsFieldClasses = mutableSetOf<Class<*>>()
        private val failedVideoItemParamsClassAbilityClasses = mutableSetOf<Class<*>>()
        private val videoItemParamsClassByLoader = mutableMapOf<ClassLoader, Class<*>?>()

        fun currentAwemeAidFromDigg(diggComponent: Any): String? {
            val fastViewPagerAbility = firstMethodResult(
                diggComponent,
                VIEW_PAGER_ACCESSOR_METHODS
            ) { methodName ->
                log("resolved viewPager accessor via fastpath name=$methodName")
            }
            val viewPagerAbility = fastViewPagerAbility ?: run {
                val componentClassLoader = diggComponent.javaClass.classLoader
                val viewPagerClass = findClass(VIEW_PAGER_ABILITY_CLASS, componentClassLoader)
                val signatureMethod = if (viewPagerClass == null) {
                    null
                } else {
                    diggComponent.javaClass.declaredMethods
                        .filter { method ->
                            !Modifier.isStatic(method.modifiers) &&
                                method.parameterTypes.isEmpty() &&
                                viewPagerClass.isAssignableFrom(method.returnType)
                        }
                        .singleOrNull()
                }
                val resolved = signatureMethod?.let {
                    callMethodQuiet(diggComponent, it.name)
                }
                if (resolved != null && signatureMethod != null) {
                    log("resolved viewPager accessor via signature name=${signatureMethod.name}")
                    resolved
                } else {
                    log("FAILED to resolve viewPager accessor")
                    null
                }
            } ?: return null

            firstAwemeAidFrom(
                viewPagerAbility,
                "LJIIIIZZ",
                "LLLLLLIL",
                "l41"
            )?.let { return it }

            firstMethodResult(viewPagerAbility, "MR", "LJLIL", "BR", "LJLIIL")?.let { currentCell ->
                aidFromAwemeLike(currentCell)?.let { return it }
            }

            return null
        }

        fun boundAwemeAidFromCommentAbility(commentAbility: Any?): String? {
            if (commentAbility == null) return null
            val abilityClass = commentAbility.javaClass
            val cachedField = synchronized(boundParamsFieldByClass) {
                boundParamsFieldByClass[abilityClass]
            }
            if (cachedField != null) {
                val cachedAid = aidFromVideoItemParams(
                    videoItemParamsFromOuterField(commentAbility, cachedField)
                )
                if (cachedAid != null) {
                    return cachedAid
                }
                // A miss does NOT mean the cached name went stale: obfuscated field
                // names cannot change within a process, but TikTok binds the params on
                // a worker thread, so an instance read before its bind completes yields
                // null transiently. Evicting here would drop a valid entry that another
                // thread just populated. Fall through to a full re-resolution instead —
                // if some other field genuinely holds the binding it is cached below,
                // otherwise the tail failure log reports the unreadable state.
            }

            for (fieldName in BOUND_PARAMS_FIELD_NAMES) {
                val videoItemParams = videoItemParamsFromOuterField(commentAbility, fieldName)
                    ?: continue
                val aid = aidFromVideoItemParams(videoItemParams) ?: continue
                cacheBoundParamsField(abilityClass, fieldName)
                log("resolved bound-params field via fastpath name=$fieldName")
                return aid
            }

            val probedFieldNames = BOUND_PARAMS_FIELD_NAMES.toMutableSet()
            var currentClass: Class<*>? = abilityClass
            while (currentClass != null && currentClass != Any::class.java) {
                for (field in currentClass.declaredFields) {
                    if (!probedFieldNames.add(field.name) || Modifier.isStatic(field.modifiers)) {
                        continue
                    }
                    val videoItemParams =
                        videoItemParamsFromOuterField(commentAbility, field.name) ?: continue
                    val aid = aidFromVideoItemParams(videoItemParams) ?: continue
                    cacheBoundParamsField(abilityClass, field.name)
                    log("resolved bound-params field via signature name=${field.name}")
                    return aid
                }
                currentClass = currentClass.superclass
            }

            logBoundParamsFailureOnce(abilityClass)
            return null
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
            return firstMethodResult(instance, methods.asList())
        }

        private fun firstMethodResult(
            instance: Any?,
            methods: List<String>,
            onResolved: ((String) -> Unit)? = null
        ): Any? {
            if (instance == null) return null
            for (method in methods) {
                val result = callMethodQuiet(instance, method)
                if (result != null) {
                    onResolved?.invoke(method)
                    return result
                }
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

        private fun videoItemParamsFromOuterField(instance: Any, outerField: String): Any? {
            val holder = getObjectField(instance, outerField, logFailure = false) ?: return null
            val videoItemParams = getObjectField(holder, "LL", logFailure = false) ?: return null
            val classLoader = instance.javaClass.classLoader
            val videoItemParamsClass = synchronized(videoItemParamsClassByLoader) {
                if (videoItemParamsClassByLoader.containsKey(classLoader)) {
                    videoItemParamsClassByLoader[classLoader]
                } else {
                    findClass(VIDEO_ITEM_PARAMS_CLASS, classLoader).also {
                        videoItemParamsClassByLoader[classLoader] = it
                    }
                }
            }
            if (videoItemParamsClass == null) {
                val abilityClass = instance.javaClass
                val shouldLogFailure = synchronized(failedVideoItemParamsClassAbilityClasses) {
                    failedVideoItemParamsClassAbilityClasses.add(abilityClass)
                }
                if (shouldLogFailure) {
                    log("FAILED to resolve VideoItemParams class name=$VIDEO_ITEM_PARAMS_CLASS")
                }
            } else if (!videoItemParamsClass.isInstance(videoItemParams)) {
                return null
            }

            val aweme = callMethodQuiet(videoItemParams, "getAweme") ?: return null
            val aid = aidFromAweme(aweme)
            return videoItemParams.takeIf { !aid.isNullOrBlank() }
        }

        private fun cacheBoundParamsField(clazz: Class<*>, fieldName: String) {
            synchronized(boundParamsFieldByClass) {
                boundParamsFieldByClass[clazz] = fieldName
            }
            synchronized(failedBoundParamsFieldClasses) {
                failedBoundParamsFieldClasses.remove(clazz)
            }
        }

        private fun logBoundParamsFailureOnce(clazz: Class<*>, detail: String? = null) {
            val shouldLogFailure = synchronized(failedBoundParamsFieldClasses) {
                failedBoundParamsFieldClasses.add(clazz)
            }
            if (shouldLogFailure) {
                val suffix = detail?.let { ": $it" }.orEmpty()
                log("FAILED to resolve bound-params field$suffix")
            }
        }

        private fun getObjectField(
            instance: Any?,
            field: String,
            logFailure: Boolean = true
        ): Any? {
            if (instance == null) return null
            return runCatching {
                XposedHelpers.getObjectField(instance, field)
            }.onFailure {
                if (logFailure) {
                    log("reflect field failed: ${instance.javaClass.name}.$field: ${it.message}")
                }
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
        private const val VIDEO_ITEM_PARAMS_CLASS =
            "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
        private const val VIEW_PAGER_ABILITY_CLASS =
            "com.ss.android.ugc.feed.platform.panel.viewpager.IViewPagerComponentAbility"
        private val VIEW_PAGER_ACCESSOR_METHODS = listOf("Yb", "Ub", "Qb", "zb")
        private val COMMENT_BIND_METHODS = listOf("hs", "up", "br", "Xq", "Nq")
        private val COMMENT_OPEN_METHODS = listOf("Id0", "Ob0", "cc0", "jc0", "Kb0")
        private val BOUND_PARAMS_FIELD_NAMES = listOf("LLJIJIL", "LLJI")

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
