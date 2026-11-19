package workbench2.motion
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean

// Reproduces the previous per-frame algorithm with the same arithmetic helpers,
// in one warmed JVM. Measures thread-allocated bytes, not Android/device power.
private class OldFlight(val from:Scene,side:Int,val mainRadius:Double,val sideRadius:Double){
    val to=from.swap(side)
    private val start=from.owners.mapIndexed { i,o -> o to Pose.fit(from.places[i],o.canvas,if(i==0)mainRadius else sideRadius) }.toMap()
    private val end=to.owners.mapIndexed { i,o -> o to Pose.fit(to.places[i],o.canvas,if(i==0)mainRadius else sideRadius) }.toMap()
    fun sample(p:Double):Map<Owner,Pose>{ease(p);return start.mapValues{(o,a)->interpolate(a,end.getValue(o),p)}}
}
@Volatile private var sink:Map<Owner,Pose>?=null
private fun scene(n:Int,mode:Int)=Scene((0 until n).map{Owner(100+it,0,1000L+it,1,Canvas(100+it*37,240+it*21,440,1))},(0 until n).map{ i-> if(i==0)Box(-0.0,0.0,900.0,1200.0) else if(mode==0)Box(960.0,(i-1)*300.0,230.0,260.0) else Box((i-1)*300.0,-0.0,260.0,230.0) },1)
fun main(){
    var checks=0
    for(n in 2..5)for(mode in 0..1)for(side in 1 until n){
        val f=scene(n,mode);val a=OldFlight(f,side,24.0,12.0);val b=Flight(1,f,side,24.0,12.0)
        for(i in 0..1000){val p=i/1000.0;val expected=a.sample(p);val actual=b.sample(p);check(expected==actual && expected.keys.toList()==actual.keys.toList());checks++}
        val mid=b.sample(.2);val next=b.sample(.4);for(o in f.owners.filter{it !in b.changedOwners})check(mid[o]===next[o]);check(b.sample(0.0)===b.sample(0.0));check(b.sample(1.0)===b.sample(1.0))
    }
    println("Exact pose/order comparisons=$checks; stationary and endpoint identity reuse verified")
    val bean=ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported);bean.isThreadAllocatedMemoryEnabled=true
    val tid=Thread.currentThread().id
    fun measure(fn:(Double)->Map<Owner,Pose>, count:Int):Double{
        val begin=bean.getThreadAllocatedBytes(tid)
        repeat(count){sink=fn((it%999+1)/1000.0)}
        return (bean.getThreadAllocatedBytes(tid)-begin).toDouble()/count
    }
    for(n in 2..5){val s=scene(n,0);val old=OldFlight(s,1,24.0,12.0);val new=Flight(1,s,1,24.0,12.0)
        val a:(Double)->Map<Owner,Pose> = old::sample;val b:(Double)->Map<Owner,Pose> = new::sample
        repeat(4){measure(a,20000);measure(b,20000)}
        val av=mutableListOf<Double>();val bv=mutableListOf<Double>();repeat(5){if(it%2==0){av+=measure(a,40000);bv+=measure(b,40000)}else{bv+=measure(b,40000);av+=measure(a,40000)}}
        val x=av.sorted()[2];val y=bv.sorted()[2]
        println("tasks=$n before_bytes_per_sample=$x after_bytes_per_sample=$y reduction_percent=${(x-y)/x*100} repetitions=5")
        check(y<=x){"Allocation regression in $n task case"}
    }
}
