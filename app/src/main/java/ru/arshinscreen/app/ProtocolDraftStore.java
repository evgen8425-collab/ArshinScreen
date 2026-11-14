package ru.arshinscreen.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Prepare an independent editable draft for each SI without generating measurements. */
public final class ProtocolDraftStore {
  private final SharedPreferences drafts;
  public ProtocolDraftStore(Context c) {
    drafts=c.getApplicationContext().getSharedPreferences("protocol_editor",Context.MODE_PRIVATE);
  }
  public boolean ensure(JSONObject device){
    if(device==null)return false;
    String method=device.optString("method").trim();
    String serial=device.optString("serial").trim();
    if(method.isEmpty()||serial.isEmpty())return false;
    String key=method+"|"+serial;
    if(drafts.contains(key))return false;
    JSONObject initial=new JSONObject();
    try{
      initial.put("method",method);
      for(String field:new String[]{"name","model","serial","registry","date"})
        initial.put(field,device.optString(field));
      initial.put("verifier","Климов А.В.");
      initial.put("rows",new JSONArray());
    }catch(Exception ignored){}
    return drafts.edit().putString(key,initial.toString()).commit();
  }
}