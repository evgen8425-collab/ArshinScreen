package ru.arshinscreen.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.util.*;

/**
 * One reusable template per method. Newly imported methods receive a blank
 * editable template; published limits are never guessed from registration IDs.
 */
public final class ProtocolTemplateStore {
  private final Context context;
  private final SharedPreferences prefs;
  public ProtocolTemplateStore(Context context) {
    this.context=context.getApplicationContext();
    prefs=this.context.getSharedPreferences("method_protocol_templates",Context.MODE_PRIVATE);
  }
  private JSONObject builtIn(String method){
    try(InputStream in=context.getAssets().open("protocol_templates.json")){
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
      while((n=in.read(chunk))>=0)bytes.write(chunk,0,n);
      JSONArray templates=new JSONObject(bytes.toString("UTF-8")).optJSONArray("templates");
      if(templates!=null)for(int i=0;i<templates.length();i++){
        JSONObject x=templates.optJSONObject(i);
        if(x!=null&&method.equalsIgnoreCase(x.optString("method")))return new JSONObject(x.toString());
      }
    }catch(Exception ignored){}
    return null;
  }
  public JSONObject get(String method,String type,String registry) {
    JSONObject template=builtIn(method);
    if(template==null){
      template=new JSONObject();
      try{
        template.put("method",method);template.put("type",type);
        template.put("registry",registry);template.put("mode","periodic");
        template.put("status","base");
        template.put("sourceNote","Требования к операциям и допускам необходимо сверить с методикой поверки.");
      }catch(Exception ignored){}
    }
    String override=prefs.getString(method,null);
    if(override!=null){
      try{
        JSONObject saved=new JSONObject(override);
        for(String k:new String[]{"operationsText","conditionsText","pointsText","notes","type","registry"}){
          if(saved.has(k))template.put(k,saved.optString(k,""));
        }
      }catch(Exception ignored){}
    }
    return template;
  }
  public void save(String method,JSONObject template){
    JSONObject edit=new JSONObject();
    try{
      for(String k:new String[]{"operationsText","conditionsText","pointsText","notes","type","registry"})
        if(template.has(k))edit.put(k,template.optString(k,""));
    }catch(Exception ignored){}
    prefs.edit().putString(method,edit.toString()).apply();
  }
}
