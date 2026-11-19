package workbench2.motion

import kotlin.math.min

/** Offline mathematical reference: no Android, Binder, Surface, or OEM dependency. */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
    init { require(listOf(x,y,w,h).all { it.isFinite() } && w > 0 && h > 0 && (x+w).isFinite() && (y+h).isFinite()) }
    val cx get() = x+w/2
    val cy get() = y+h/2
    fun contains(px: Double, py: Double, tolerance: Double = 0.0) =
        px >= x-tolerance && px <= x+w+tolerance && py >= y-tolerance && py <= y+h+tolerance
}
data class Canvas(val width: Int, val height: Int, val dpi: Int, val contentEpoch: Long) {
    init { require(width >= 2 && height >= 2 && dpi > 0 && contentEpoch >= 0) }
}
data class Owner(val task: Int, val user: Int, val token: Long, val leashEpoch: Long, val canvas: Canvas) {
    init { require(task >= 0 && user >= 0 && token > 0 && leashEpoch >= 0) }
}
data class Point(val x: Double, val y: Double)
data class Pose(val box: Box, val scale: Double, val radiusPx: Double, val canvas: Canvas) {
    init {
        require(scale.isFinite() && scale > 0 && radiusPx.isFinite() && radiusPx >= 0)
        require(scale*canvas.width <= box.w+1e-8 && scale*canvas.height <= box.h+1e-8)
        require(radiusPx <= min(scale*canvas.width,scale*canvas.height)/2+1e-8)
    }
    val content get() = Box(box.cx-scale*canvas.width/2,box.cy-scale*canvas.height/2,scale*canvas.width,scale*canvas.height)
    val localRadius get() = radiusPx/scale
    fun project(p: Point) = Point(content.x+p.x*scale,content.y+p.y*scale)
    fun inverse(p: Point): Point? = if (!content.contains(p.x,p.y,1e-8)) null else
        Point((p.x-content.x)/scale,(p.y-content.y)/scale)
    companion object {
        fun fit(box: Box, canvas: Canvas, radiusPx: Double): Pose {
            require(radiusPx.isFinite() && radiusPx >= 0)
            val z=min(box.w/canvas.width,box.h/canvas.height)
            return Pose(box,z,min(radiusPx,min(z*canvas.width,z*canvas.height)/2),canvas)
        }
    }
}
private fun blend(a: Double,b: Double,u: Double) = a+(b-a)*u
/** Single scalar clock for both cards. Equal logical canvas is necessary, not pixel proof. */
fun ease(p: Double): Double {
    require(p.isFinite() && p in 0.0..1.0)
    return p*p*(3-2*p)
}
fun interpolate(a: Pose,b: Pose,p: Double): Pose {
    require(a.canvas==b.canvas)
    val u=ease(p)
    if(p==0.0) return a
    if(p==1.0) return b
    return interpolateInterior(a,b,u)
}
private fun interpolateInterior(a: Pose,b: Pose,u: Double): Pose {
    // Linear convex combination of boxes and isotropic scales ensures the entire
    // source canvas remains inside the viewport, even when aspect ratios differ.
    val box=Box(blend(a.box.x,b.box.x,u),blend(a.box.y,b.box.y,u),blend(a.box.w,b.box.w,u),blend(a.box.h,b.box.h,u))
    return Pose(box,blend(a.scale,b.scale,u),blend(a.radiusPx,b.radiusPx,u),a.canvas)
}
data class Scene(val owners: List<Owner>, val places: List<Box>, val displayEpoch: Long) {
    init { require(owners.size in 1..5 && places.size==owners.size && owners.map { it.task }.distinct().size==owners.size && displayEpoch>=0) }
    fun swap(side: Int): Scene {
        require(side in 1 until owners.size)
        val next=owners.toMutableList(); val a=next[0]; next[0]=next[side]; next[side]=a
        return copy(owners=next.toList())
    }
}
/** A frame is a pure plan, not proof of transaction submission, latch or physical presentation. */
class Flight(val generation: Long,val from: Scene,val side: Int,val mainRadius: Double,val sideRadius: Double) {
    init { require(generation>0 && mainRadius>=0 && sideRadius>=0 && mainRadius.isFinite() && sideRadius.isFinite()) }
    val to=from.swap(side)
    val changedOwners=java.util.Collections.unmodifiableSet(setOf(from.owners[0],from.owners[side]))
    private val start=java.util.Collections.unmodifiableMap(from.owners.mapIndexed { i,o ->
        o to Pose.fit(from.places[i],o.canvas,if(i==0)mainRadius else sideRadius) }.toMap())
    // Preserve source iteration order even at the final endpoint.
    private val end=java.util.Collections.unmodifiableMap(start.mapValues { (o,a) ->
        if(o !in changedOwners) a else { val i=to.owners.indexOf(o); Pose.fit(to.places[i],o.canvas,if(i==0)mainRadius else sideRadius) }
    })
    // Nonmoving cards have constant interior poses. Compute once, including
    // the old interpolation's signed-zero behavior, rather than every frame.
    private val interior=start.mapValues { (o,a) ->
        if(o in changedOwners) a else interpolateInterior(a,a,0.5)
    }
    fun sample(p: Double): Map<Owner,Pose> {
        val u=ease(p)
        if(p==0.0)return start
        if(p==1.0)return end
        // mapValues creates only the new frame map, without copying a second
        // map or wrapping each interior sample. Never expose the cached map.
        return interior.mapValues { (o,a) ->
            if(o in changedOwners) interpolateInterior(a,end.getValue(o),u) else a
        }
    }
}
