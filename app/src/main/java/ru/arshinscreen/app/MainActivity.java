package ru.arshinscreen.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final String API="https://fgis.gost.ru/fundmetrology/eapi/vri";
  private static final int PICK_FOLDER=1, PICK_SAVE=2;
  private final ExecutorService pool=Executors.newSingleThreadExecutor();
  private LinearLayout body, matches;
  private TextView message;
  private EditText serial, verifier, from, to;
  private Uri folder;
  private byte[] currentPdf;
  private String currentName;
  private final ArrayList<JSONObject> records=new ArrayList<>();
  private static final int BLUE=0xff15385a;
  private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
  private void addFieldLabel(String s){TextView t=label(s,14);t.setTextColor(BLUE);body.addView(t);}
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xfff7f9fc);getWindow().setNavigationBarColor(0xfff7f9fc);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);String u=getPreferences(0).getString("folder",null);if(u!=null)folder=Uri.parse(u);searchScreen();}
  private TextView label(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setPadding(8,12,8,12);return t;}
  private void button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(48));body.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
  private EditText input(String hint,String value){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setHint(hint);e.setText(value);e.setSelectAllOnFocus(false);body.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));return e;}
  private void page(String title){
    ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(16),dp(12),dp(16),dp(28));scroll.addView(body);setContentView(scroll);
    TextView heading=label(title,22);heading.setTextColor(BLUE);heading.setTypeface(null,1);body.addView(heading);
    LinearLayout tabs=new LinearLayout(this);body.addView(tabs);
    for(String s:new String[]{"Поиск","Настройки","Файлы"}){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(dp(44));b.setPadding(dp(2),0,dp(2),0);tabs.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));b.setOnClickListener(v->{if(s.equals("Поиск"))searchScreen();else if(s.equals("Настройки"))settings();else files();});}
    message=label("",14);body.addView(message);
  }
  private void info(String s){runOnUiThread(()->{if(message!=null)message.setText(s);});}
  private String pref(String k,String d){return getPreferences(0).getString(k,d);}
  private String today(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
  private void datePicker(EditText e){Calendar c=Calendar.getInstance();try{String[] a=e.getText().toString().split("-");c.set(Integer.parseInt(a[0]),Integer.parseInt(a[1])-1,Integer.parseInt(a[2]));}catch(Exception ignored){}
    new DatePickerDialog(this,(v,y,m,d)->e.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),c.get(1),c.get(2),c.get(5)).show();}
  private void searchScreen(){
    page("Аршин Скрин");body.addView(label("Поиск поверки СИ",18));
    addFieldLabel("Заводской номер");serial=input("Введите заводской номер",pref("serial",""));addFieldLabel("Поверитель (необязательно)");verifier=input("Название организации",pref("verifier",""));
    addFieldLabel("Дата поверки с");from=input("ГГГГ-ММ-ДД",pref("from","2026-01-01"));from.setShowSoftInputOnFocus(false);from.setOnClickListener(v->datePicker(from));
    addFieldLabel("Дата поверки по");to=input("ГГГГ-ММ-ДД",pref("to",today()));to.setShowSoftInputOnFocus(false);to.setOnClickListener(v->datePicker(to));
    button("Найти в Аршине",this::search);
    matches=new LinearLayout(this);matches.setOrientation(1);body.addView(matches);
  }
  private static String pick(JSONObject j,String... keys){for(String k:keys){String x=j.optString(k,"");if(!x.isEmpty()&&!x.equals("null"))return x;}return "";}
  private static String enc(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
  private String get(String url)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/json");
    try{if(c.getResponseCode()!=200)throw new IOException("Ответ Аршина HTTP "+c.getResponseCode());
      ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>7000000)throw new IOException("Ответ слишком большой");}}
      return out.toString("UTF-8");
    }finally{c.disconnect();}
  }
  private JSONArray list(JSONObject j){JSONArray a=j.optJSONArray("items");if(a!=null)return a;Object o=j.opt("result");if(o instanceof JSONArray)return (JSONArray)o;if(o instanceof JSONObject){JSONObject r=(JSONObject)o;a=r.optJSONArray("items");if(a!=null)return a;return r.optJSONArray("data");}return j.optJSONArray("data");}
  private String date(String raw){if(raw.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*"))return raw.substring(6,10)+"-"+raw.substring(3,5)+"-"+raw.substring(0,2);return raw.length()>=10?raw.substring(0,10):raw;}
  private void search(){
    String n=serial.getText().toString().trim(),org=verifier.getText().toString().trim(),a=from.getText().toString(),b=to.getText().toString();
    if(n.isEmpty()){info("Введите заводской номер");return;}if(!a.matches("\\d{4}-\\d{2}-\\d{2}")||!b.matches("\\d{4}-\\d{2}-\\d{2}")||a.compareTo(b)>0){info("Проверьте диапазон дат");return;}
    getPreferences(0).edit().putString("serial",n).putString("verifier",org).putString("from",a).putString("to",b).apply();
    matches.removeAllViews();records.clear();info("Ищу поверки...");
    pool.execute(()->{try{boolean truncated=false;
      for(int p=0;p<10;p++){
        JSONObject response=new JSONObject(get(API+"?mi_number="+enc(n)+"&start="+(p*100)+"&rows=100"));
        JSONArray arr=list(response);if(arr==null)throw new IOException("Неизвестная структура ответа");
        for(int i=0;i<arr.length();i++){JSONObject r=arr.optJSONObject(i);if(r==null)continue;
          String no=pick(r,"mi_number","mi.number");if(!no.isEmpty()&&!no.equalsIgnoreCase(n))continue;
          String d=pick(r,"verification_date");if(!d.isEmpty()&&(date(d).compareTo(a)<0||date(d).compareTo(b)>0))continue;
          if(!org.isEmpty()&&!r.toString().toLowerCase(Locale.ROOT).contains(org.toLowerCase(Locale.ROOT)))continue;
          records.add(r);
        }
        if(arr.length()<100)break;if(p==9)truncated=true;
      }
      boolean cut=truncated;runOnUiThread(()->showMatches(cut));
    }catch(Exception e){info("Ошибка поиска: "+e.getMessage());}});
  }
  private void showMatches(boolean cut){matches.removeAllViews();info("Найдено: "+records.size()+(cut?" (поиск ограничен первыми 1000 записями)":""));
    for(JSONObject r:records){String id=pick(r,"vri_id","id");Button b=new Button(this);
      b.setAllCaps(false);b.setText(pick(r,"mi_number","mi.number")+" • "+pick(r,"verification_date")+"\n"+pick(r,"mi_type","mi.mititle","mitype_title")+"\n"+pick(r,"org_title","organization")+"\nЗапись: "+id);matches.addView(b);b.setOnClickListener(v->details(id));}
  }
  private void details(String id){if(id.isEmpty()){info("У записи нет идентификатора");return;}info("Загружаю карточку...");
    pool.execute(()->{try{JSONObject o=new JSONObject(get(API+"/"+enc(id)));runOnUiThread(()->recordScreen(id,o));}catch(Exception e){info("Ошибка получения записи: "+e.getMessage());}});}
  private void recordScreen(String id,JSONObject data){page("Сведения о поверке");body.addView(label("Запись ФИФ: "+id,16));
    ArrayList<String> lines=new ArrayList<>();flatten("",data,lines,0);
    for(String s:lines)body.addView(label(s,13));
    button("Сохранить полный PDF",()->savePdf(id,lines));
    button("Сохранить PDF на Google Диск",()->{try{ArrayList<String> all=new ArrayList<>();all.add("Сведения о результатах поверки СИ");all.add("Источник: ФГИС Аршин");all.add("Номер записи: "+id);all.add("https://fgis.gost.ru/fundmetrology/eapi/vri/"+id);all.add("");all.addAll(lines);currentPdf=pdf(all);currentName="Аршин_"+id.replaceAll("[^A-Za-z0-9_-]","_")+".pdf";chooseSave();info("В открывшемся окне выберите Google Диск и нужную папку.");}catch(Exception e){info(e.getMessage());}});
    button("Вернуться к поиску",this::searchScreen);
  }
  private void flatten(String prefix,Object value,ArrayList<String> out,int depth){
    if(depth>12||out.size()>2000)return;
    if(value instanceof JSONObject){JSONObject j=(JSONObject)value;Iterator<String> it=j.keys();while(it.hasNext()){String k=it.next();flatten(prefix.isEmpty()?k:prefix+" / "+k,j.opt(k),out,depth+1);}}
    else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)flatten(prefix+" ["+(i+1)+"]",a.opt(i),out,depth+1);}
    else if(value!=null&&value!=JSONObject.NULL)out.add(prefix+": "+String.valueOf(value));
  }
  private void savePdf(String id,ArrayList<String> lines){
    try{ArrayList<String> all=new ArrayList<>();all.add("Сведения о результатах поверки СИ");all.add("Источник: ФГИС Аршин");all.add("Номер записи: "+id);all.add("https://fgis.gost.ru/fundmetrology/eapi/vri/"+id);all.add("");all.addAll(lines);
      currentPdf=pdf(all);currentName="Аршин_"+id.replaceAll("[^A-Za-z0-9_-]","_")+"_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".pdf";
      if(folder==null){chooseSave();return;}
      try{String docId=DocumentsContract.getTreeDocumentId(folder);Uri parent=DocumentsContract.buildDocumentUriUsingTree(folder,docId);
        Uri dest=DocumentsContract.createDocument(getContentResolver(),parent,"application/pdf",currentName);if(dest==null)throw new IOException("Нет доступа");
        write(dest);info("PDF сохранён: "+currentName);}
      catch(Exception e){info("Не удалось сохранить в папку, выберите файл вручную");chooseSave();}
    }catch(Exception e){info("Ошибка PDF: "+e.getMessage());}
  }
  private void write(Uri uri)throws Exception{try(OutputStream o=getContentResolver().openOutputStream(uri,"w")){if(o==null)throw new IOException("Нет доступа к файлу");o.write(currentPdf);}getPreferences(0).edit().putString("last",currentName).apply();}
  private void chooseSave(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");i.putExtra(Intent.EXTRA_TITLE,currentName);startActivityForResult(i,PICK_SAVE);}
  private byte[] pdf(ArrayList<String> raw)throws Exception{
    final int w=595,h=842,margin=38;Paint p=new Paint(3);p.setTypeface(Typeface.create("sans-serif",0));p.setTextSize(11);p.setColor(0xff222222);
    ArrayList<String> lines=new ArrayList<>();for(String s:raw){if(s.isEmpty()){lines.add("");continue;}String rem=s;while(!rem.isEmpty()){int len=p.breakText(rem,true,w-2*margin,null);if(len<=0)len=1;lines.add(rem.substring(0,len));rem=rem.substring(len);}}
    PdfDocument doc=new PdfDocument();int at=0,page=1;do{PdfDocument.Page pg=doc.startPage(new PdfDocument.PageInfo.Builder(w,h,page).create());Canvas canvas=pg.getCanvas();int y=margin+12;
      while(at<lines.size()&&y<h-margin){canvas.drawText(lines.get(at++),margin,y,p);y+=16;}canvas.drawText("ФГИС Аршин • "+page,margin,h-20,p);doc.finishPage(pg);page++;
    }while(at<lines.size());ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);doc.close();return out.toByteArray();
  }
  private void settings(){page("Настройки");body.addView(label("Формат: PDF",17));
    body.addView(label("Локальная папка: Протоколы поверки СИ",16));body.addView(label(folder==null?"Выберите папку один раз":"Локальная папка подключена",14));
    button("Выбрать папку на смартфоне",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_FOLDER);});
    body.addView(label("Google Диск: при просмотре поверки нажмите «Сохранить PDF на Google Диск» и выберите папку через системное окно. Автоматическая синхронизация ещё не реализована.",15));
    button("Открыть Google Диск",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://drive.google.com/drive/u/0/my-drive"))));
  }
  private void files(){page("Сохранённые PDF");body.addView(label("Последний PDF: "+pref("last","ещё нет"),15));body.addView(label("Полная история и синхронизация с Google Диском — следующие этапы разработки.",14));}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
    try{if(request==PICK_FOLDER){Uri u=data.getData();getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);folder=u;getPreferences(0).edit().putString("folder",u.toString()).apply();settings();}
      else if(request==PICK_SAVE&&currentPdf!=null){write(data.getData());info("PDF сохранён: "+currentName);}}
    catch(Exception e){info("Ошибка сохранения: "+e.getMessage());}
  }
  @Override protected void onDestroy(){pool.shutdownNow();super.onDestroy();}
}
