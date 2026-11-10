import java.lang.reflect.*;
public class TestRunner {
  public static void main(String[]args)throws Exception{
    var c=Class.forName("io.github.x647368maker.atomicworkbench.display.ExchangeSessionTest");
    int passed=0,failed=0;
    for(var m:c.getDeclaredMethods()) if(m.isAnnotationPresent(org.junit.Test.class)) {
      try {m.invoke(c.getDeclaredConstructor().newInstance());passed++;System.out.println("PASS "+m.getName());}
      catch(InvocationTargetException e){failed++;System.err.println("FAIL "+m.getName());e.getCause().printStackTrace();}
    }
    System.out.printf("RESULT %d passed, %d failed%n",passed,failed);
    if(failed>0 || passed<12) System.exit(1);
  }
}
