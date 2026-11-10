class ExchangeSessionTest {
    static class Tasks implements ExchangeSession.Driver<String> {
        int mainDisplay=0, slotDisplay=3, promotions, demotions;
        boolean gone, partialFailure;
        public boolean atOrigins(ExchangeSession.Origin<String> main, ExchangeSession.Origin<String> slot) { return mainDisplay==0 && slotDisplay==3; }
        public void promote(ExchangeSession.Origin<String> slot) {
            promotions++; slotDisplay=0;
            if (partialFailure) throw new IllegalStateException("Binder mutated before observation failed");
        }
        public boolean readyForDemotion(ExchangeSession.Origin<String> main, ExchangeSession.Origin<String> slot) { return !gone && mainDisplay==0 && slotDisplay==0; }
        public void demoteAndFit(ExchangeSession.Origin<String> main, ExchangeSession.Origin<String> slot) { demotions++; mainDisplay=3; }
        public boolean committed(ExchangeSession.Origin<String> main, ExchangeSession.Origin<String> slot) { return mainDisplay==3 && slotDisplay==0; }
        public boolean restoreAndVerify(ExchangeSession.Origin<String> main, ExchangeSession.Origin<String> slot) {
            if (gone) return false;
            mainDisplay=0; slotDisplay=3; return atOrigins(main,slot);
        }
    }
    public static void main(String[] args) {
        for (String path : new String[]{"normal","cancel","deadline","disappeared","partialFailure"}) {
            var main=new ExchangeSession.Origin<>(1,0,0,new Object(),"main layout");
            var slot=new ExchangeSession.Origin<>(2,0,3,new Object(),"slot layout");
            var s=new ExchangeSession<>(7,100,main,slot);
            var d=new Tasks(); d.partialFailure=path.equals("partialFailure");
            s.advance(6,s.phase(),0,d); assert d.promotions==0;
            s.advance(7,s.phase(),1,d); assert !s.canRelease() && d.promotions==1;
            s.advance(7,ExchangeSession.Phase.PREPARED,2,d); assert d.demotions==0;
            if(path.equals("cancel"))s.cancel();
            d.gone=path.equals("disappeared");
            s.advance(7,s.phase(),path.equals("deadline")?101:2,d);
            if(s.phase()==ExchangeSession.Phase.ROLLBACK_PENDING)s.advance(7,s.phase(),102,d);
            if(d.gone)assert !s.canRelease() && s.phase()==ExchangeSession.Phase.RECOVERY_REQUIRED;
            else if(path.equals("normal"))assert s.canRelease() && d.mainDisplay==3 && d.slotDisplay==0;
            else assert s.canRelease() && d.mainDisplay==0 && d.slotDisplay==3;
            s.advance(7,s.phase(),103,d); assert d.promotions==1 && d.demotions==(path.equals("normal")?1:0);
            System.out.println(path+" "+s.phase());
        }
    }
}
