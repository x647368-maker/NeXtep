package org.junit;
public class Assert {
    public static void assertEquals(Object a,Object b) {if(!java.util.Objects.equals(a,b)) throw new AssertionError("expected="+a+" actual="+b);}
    public static void assertEquals(int a,int b) {if(a!=b) throw new AssertionError("expected="+a+" actual="+b);}
    public static void assertFalse(boolean b) {if(b) throw new AssertionError("expected false");}
    public static void assertTrue(boolean b) {if(!b) throw new AssertionError("expected true");}
    public static void assertNull(Object b) {if(b!=null) throw new AssertionError("expected null actual="+b);}
}
