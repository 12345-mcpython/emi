package dev.emi.emi.runtime;

import java.util.*;
import dev.emi.emi.api.stack.*;
import net.minecraft.item.ItemStack;

public class BookmarkMaterialTransferChecks {
 static class Fake implements BookmarkMaterialTransfer.Access {
  List<ItemStack> slots=new ArrayList<>(List.of(new ItemStack("iron",64),new ItemStack("",0),new ItemStack("",0)));
  List<ItemStack> server=new ArrayList<>(); ItemStack cursor=new ItemStack("",0),scursor=cursor.copy();
  long time,version,cv; long[] sv=new long[3]; boolean valid=true,ack=true,insert=true,take=true;int cap=64,clicks;
  Fake(){slots.forEach(s->server.add(s.copy()));}
  public boolean valid(){return valid;}public long time(){return time;}public ItemStack stack(int s){return slots.get(s);}public ItemStack cursor(){return cursor;}
  public List<Integer> sources(){return List.of(0);}public List<Integer> destinations(){return List.of(1,2);}
  public boolean take(int s){return take;}public boolean insert(int s,ItemStack st){return insert;}public int capacity(int s,ItemStack st){return cap;}
  public long slotVersion(int s){return sv[s];}public long cursorVersion(){return cv;}public ItemStack serverStack(int s){return server.get(s);}public ItemStack serverCursor(){return scursor;}
  public void click(int s,int b){clicks++;ItemStack at=slots.get(s);if(b==0){if(cursor.isEmpty()){cursor=at;slots.set(s,new ItemStack("",0));}else{slots.set(s,new ItemStack(cursor.id,cursor.count+at.count));cursor=new ItemStack("",0);}}else{slots.set(s,new ItemStack(cursor.id,at.count+1));cursor=new ItemStack(cursor.id,cursor.count-1);}if(ack)ack();}
  void ack(){for(int i=0;i<3;i++){server.set(i,slots.get(i).copy());sv[i]=++version;}scursor=cursor.copy();cv=++version;}
 }
 static class ServerFake extends Fake {
  int fills;
  public boolean exactSupported(){return true;}
  public void transferExact(int source,int destination,int count){fills++;ItemStack s=slots.get(source);slots.set(source,new ItemStack(s.id,s.count-count));slots.set(destination,new ItemStack(s.id,count));if(ack){server.set(source,slots.get(source).copy());sv[source]=++version;server.set(destination,slots.get(destination).copy());sv[destination]=++version;}}
 }
 static BookmarkMaterialTransfer.Runner runner(Fake f,long n){return new BookmarkMaterialTransfer.Runner(f,List.of(new BookmarkMaterialTransfer.Need(new ItemEmiStack("iron"),n)));}
 static void run(Fake f,BookmarkMaterialTransfer.Runner r,int max){for(int i=0;i<max&&r.running;i++){f.time++;r.tick();}}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  Fake f=new Fake();var r=runner(f,3);run(f,r,20);check(!r.running&&r.reason.equals("budget_complete"),"completion");check(r.completed==3&&f.slots.get(0).count==61&&f.slots.get(1).count==3&&f.cursor.isEmpty(),"exact 3 of 64");check(f.clicks==5,"whole pickup + 3 right clicks + return");
  f=new Fake();f.slots.set(1,new ItemStack("iron",63));r=runner(f,3);run(f,r,20);check(f.slots.get(1).count==64&&f.slots.get(2).count==2&&f.slots.get(0).count==61,"partial destinations");
  f=new Fake();f.ack=false;r=runner(f,3);run(f,r,50);check(r.completed==0&&f.clicks==1,"prediction not acknowledgement");run(f,r,200);check(!r.running&&r.reason.equals("server_ack_timeout")&&f.cursor.isEmpty()&&f.slots.get(0).count==64,"timeout returns whole source");
  f=new Fake();r=runner(f,3);run(f,r,1);r.cancelReason="user_cancelled";run(f,r,10);check(r.reason.equals("user_cancelled")&&f.slots.get(0).count==64&&f.cursor.isEmpty(),"cancel return");
  f=new Fake();r=runner(f,3);run(f,r,2);r.cancelReason="user_cancelled";run(f,r,10);check(r.completed==1&&f.slots.get(0).count==63&&f.slots.get(1).count==1&&f.cursor.isEmpty(),"cancel after exact deposit");
  f=new Fake();r=runner(f,3);run(f,r,1);f.valid=false;run(f,r,10);check(f.clicks==1&&r.reason.equals("screen_changed_cursor_remaining"),"screen change no clicks");
  f=new Fake();f.cursor=new ItemStack("other",1);r=runner(f,3);run(f,r,2);check(f.clicks==0&&r.reason.equals("cursor_changed"),"foreign cursor rejected");
  f=new Fake();f.take=false;r=runner(f,3);run(f,r,5);check(f.clicks==0,"take permission");
  f=new Fake();f.insert=false;r=runner(f,3);run(f,r,5);check(f.clicks==0,"insert permission");
  f=new Fake();f.slots.set(1,new ItemStack("iron",64));f.slots.set(2,new ItemStack("other",64));r=runner(f,3);run(f,r,5);check(f.clicks==0&&r.reason.equals("inventory_full"),"full inventory");
  f=new Fake();r=runner(f,Long.MAX_VALUE);run(f,r,5000);check(r.operations<=4096&&f.cursor.isEmpty(),"bounded operations");
  f=new Fake();r=runner(f,3);r.tick();r.tick();check(f.clicks==1,"one action per tick");
  boolean unsupported=false;try{BookmarkMaterialTransfer.add(new ArrayList<>(),new EmiStack("fluid"),1000);}catch(UnsupportedOperationException e){unsupported=true;}check(unsupported,"fluid unsupported");
  ServerFake sf=new ServerFake();r=runner(sf,3);run(sf,r,20);check(r.reason.equals("budget_complete")&&r.completed==3&&sf.slots.get(0).count==61&&sf.slots.get(1).count==3&&sf.fills==1&&sf.clicks==0&&sf.cursor.isEmpty(),"server exact 3 of 64 without cursor packet");
  sf=new ServerFake();sf.cap=2;r=runner(sf,3);run(sf,r,20);check(r.completed==3&&sf.fills==2&&sf.slots.get(1).count==2&&sf.slots.get(2).count==1,"server split across capacities");
  sf=new ServerFake();sf.slots.set(1,new ItemStack("iron",63));r=runner(sf,3);run(sf,r,20);check(sf.slots.get(1).count==63&&sf.slots.get(2).count==3,"server occupied destination excluded");
  sf=new ServerFake();sf.ack=false;r=runner(sf,3);run(sf,r,200);check(r.reason.equals("server_ack_timeout")&&r.completed==0&&sf.fills==1&&sf.clicks==0,"server no prediction acknowledgement or retry");
  sf=new ServerFake();sf.ack=false;r=runner(sf,3);run(sf,r,1);sf.server.set(0,sf.slots.get(0).copy());sf.sv[0]=++sf.version;run(sf,r,1);check(r.completed==0,"both source and destination acknowledgement required");sf.server.set(1,sf.slots.get(1).copy());sf.sv[1]=++sf.version;run(sf,r,2);check(r.completed==3,"server both packets complete");
  sf=new ServerFake();r=runner(sf,3);run(sf,r,1);r.cancelReason="user_cancelled";run(sf,r,5);check(r.reason.equals("user_cancelled")&&r.completed==3&&sf.fills==1&&sf.cursor.isEmpty(),"cancel waits in-flight server transfer");
  System.out.println("BookmarkMaterialTransfer checks passed (click and EMI server exact, partial, raw ack, cancel, screen, cursor, permissions, full, bounds, fluid)");
 }
}
