package xdm.app

import java.lang.reflect.Method

/**
 * Every settable [IAppConfig] value at one moment, read through the interface's getter/setter pairs,
 * so a new config field is covered without touching this class.
 *
 * For an editor that stays open while the rest of the app keeps changing the config (Settings, now
 * that it is not modal): it writes every value it shows, so on its own it would undo a change made
 * elsewhere meanwhile, like the download-complete window's "don't show again" or a folder a download
 * dialog remembered. [keepChangedElsewhere] puts those back.
 */
class ConfigSnapshot private constructor(private val values: Map<Method, Any?>) {

    /**
     * Called on the snapshot taken when the editor loaded its values, after it wrote them over [config].
     * Every value the editor left as it found it, but which [beforeWrite] (taken just before the
     * editor wrote) shows was changed meanwhile, gets that newer value back. Where both changed one,
     * the editor's value stays: the user just chose it.
     */
    fun keepChangedElsewhere(config: IAppConfig, beforeWrite: ConfigSnapshot) {
        for ((getter, setter) in properties) {
            val loaded = values[getter]
            val elsewhere = beforeWrite.values[getter]
            if (elsewhere != loaded && getter.invoke(config) == loaded) setter.invoke(config, elsewhere)
        }
    }

    companion object {
        /** `getX`/`isX` with a matching `setX`: the interface's `var`s, the inherited core ones included. */
        private val properties: List<Pair<Method, Method>> by lazy {
            val methods = IAppConfig::class.java.methods
            methods.filter { it.parameterCount == 0 && it.returnType != Void.TYPE }.mapNotNull { getter ->
                val name = when {
                    getter.name.startsWith("get") -> getter.name.removePrefix("get")
                    getter.name.startsWith("is") -> getter.name.removePrefix("is")
                    else -> return@mapNotNull null
                }
                methods.firstOrNull {
                    it.name == "set$name" && it.parameterCount == 1 && it.parameterTypes[0] == getter.returnType
                }?.let { getter to it }
            }
        }

        fun of(config: IAppConfig) = ConfigSnapshot(properties.associate { (getter, _) -> getter to getter.invoke(config) })
    }
}
