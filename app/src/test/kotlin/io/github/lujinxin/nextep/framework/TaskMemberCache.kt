package io.github.x647368maker.atomicworkbench.framework

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Cache successful metadata only. Values, missing members and access failures are not cached. */
internal object TaskMemberCache {
    private val fields=ConcurrentHashMap<Class<*>,ConcurrentHashMap<String,Field>>()
    private val methods=ConcurrentHashMap<Class<*>,ConcurrentHashMap<String,Method>>()
    fun field(type:Class<*>,name:String):Field {
        return fields[type]?.get(name) ?: type.getDeclaredField(name).apply{isAccessible=true}.also{
            fields.computeIfAbsent(type){ConcurrentHashMap()}.putIfAbsent(name,it)
        }
    }
    fun method(type:Class<*>,name:String):Method {
        return methods[type]?.get(name) ?: type.getDeclaredMethod(name).apply{isAccessible=true}.also{
            methods.computeIfAbsent(type){ConcurrentHashMap()}.putIfAbsent(name,it)
        }
    }
    fun invalidateField(type:Class<*>,name:String){fields[type]?.remove(name)}
    fun invalidateMethod(type:Class<*>,name:String){methods[type]?.remove(name)}
}
