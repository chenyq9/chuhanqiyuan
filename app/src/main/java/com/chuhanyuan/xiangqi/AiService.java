package com.chuhanyuan.xiangqi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI兼容 /chat/completions 客户端。工具只描述“真人在棋盘上做什么”，不强加象棋规则。 */
public class AiService {
    public static class Move {
        public String action="move_piece";
        public String from="",to="",say="";
        public int fc,fr,tc,tr;
        public String capturedSide="";
        public char pieceKind=0;
        public int occurrence=1;
    }

    private final String base,key,model;
    private final List<String[]> history=new ArrayList<>();
    private static final String SYSTEM_PROMPT=
            "你正在和朋友面对面下中国象棋，但这是一张模拟真实棋盘的自由棋盘。最重要的是像真人朋友：拿起一颗棋子，放到另一格；不要因为传统象棋规则而拒绝走法。任何棋子都可以移动到任何空格，终点若有对方棋子就表示吃子。\n"+
            "你执红棋、坐在棋盘上方（行0一侧），朋友执黑棋、坐在下方（行9一侧）。坐标列a-i从左到右、行0-9从上到下。\n"+
            "默认轮到你时用 move_piece 移动自己的红棋；你可以吃朋友的黑棋。只有朋友明确让你替他走、让你帮他操作时，才用 move_friend_piece 移动朋友的黑棋。朋友明确让你把已经吃掉的棋重新放回棋盘时，可以用 return_captured_piece。一次回复只做一个实际的物理动作。可以顺便说一句很自然的话，也可以不说。";

    private static final String TOOLS=
            "["+
            "{\"type\":\"function\",\"function\":{\"name\":\"move_piece\",\"description\":\"移动AI自己的红棋。目标为空=落子，目标有朋友黑棋=吃子。完全不按传统象棋规则限制。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"say\":{\"type\":\"string\"}},\"required\":[\"from\",\"to\",\"say\"]}}},"+
            "{\"type\":\"function\",\"function\":{\"name\":\"move_friend_piece\",\"description\":\"只在朋友明确让你帮他走棋时使用，移动朋友的黑棋。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"say\":{\"type\":\"string\"}},\"required\":[\"from\",\"to\",\"say\"]}}},"+
            "{\"type\":\"function\",\"function\":{\"name\":\"return_captured_piece\",\"description\":\"把已经被吃、正在战利品区的一颗棋子重新放到指定空格。capturedSide填red或black；piece填棋子汉字；occurrence是同名棋子的序号，从1开始。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"capturedSide\":{\"type\":\"string\",\"enum\":[\"red\",\"black\"]},\"piece\":{\"type\":\"string\"},\"occurrence\":{\"type\":\"integer\"},\"to\":{\"type\":\"string\"},\"say\":{\"type\":\"string\"}},\"required\":[\"capturedSide\",\"piece\",\"occurrence\",\"to\",\"say\"]}}}"+
            "]";

    public AiService(String base,String key,String model){String b=base==null?"":base.trim();while(b.endsWith("/"))b=b.substring(0,b.length()-1);this.base=b;this.key=key==null?"":key.trim();this.model=model==null?"":model.trim();}
    public void resetHistory(){history.clear();}
    public void noteToolMiss(String fromTo){history.add(new String[]{"user","（系统：你上一手想做 "+fromTo+"，但现场情况不允许，棋盘没有变化。重新观察。）"});}
    public String chat(String userMessage)throws Exception{history.add(new String[]{"user",userMessage});String resp=post(buildBody());history.add(new String[]{"assistant",summarize(resp)});while(history.size()>24)history.remove(0);return resp;}
    private String summarize(String resp){Move m=firstMove(resp);String c=content(resp);if(m!=null)return "["+m.action+" "+m.from+"→"+m.to+"]"+(m.say==null?"":m.say);return c==null?"":c;}
    private String buildBody(){StringBuilder sb=new StringBuilder();sb.append("{\"model\":\"").append(esc(model)).append("\",\"temperature\":0.8,\"tools\":").append(TOOLS).append(",\"tool_choice\":\"auto\",\"messages\":[{\"role\":\"system\",\"content\":\"").append(esc(SYSTEM_PROMPT)).append("\"}");for(String[] m:history)sb.append(",{\"role\":\"").append(m[0]).append("\",\"content\":\"").append(esc(m[1])).append("\"}");sb.append("]}");return sb.toString();}
    private String post(String body)throws Exception{String endpoint=base;if(endpoint==null||endpoint.trim().isEmpty())endpoint="https://api.openai.com/v1";if(!endpoint.endsWith("/chat/completions")){if(!endpoint.endsWith("/"))endpoint+="/";endpoint+="chat/completions";}HttpURLConnection conn=(HttpURLConnection)new URL(endpoint).openConnection();conn.setRequestMethod("POST");conn.setConnectTimeout(20000);conn.setReadTimeout(90000);conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer "+key);try(OutputStream os=conn.getOutputStream()){os.write(body.getBytes(StandardCharsets.UTF_8));}int code=conn.getResponseCode();java.io.InputStream is=code>=400?conn.getErrorStream():conn.getInputStream();if(is==null)is=conn.getInputStream();StringBuilder out=new StringBuilder();try(BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String ln;while((ln=br.readLine())!=null)out.append(ln);}String resp=out.toString();if(code>=400)throw new Exception("HTTP "+code+" "+resp.substring(0,Math.min(resp.length(),250)));return resp;}

    public static String content(String body){int i=indexOfKey(body,"content");return i<0?null:readStringAt(body,i);}
    public static Move firstMove(String body){
        int tc=body.indexOf("\"tool_calls\"");if(tc<0)return null;
        int fn=body.indexOf("\"function\"",tc);if(fn<0)return null;
        int namePos=indexOfKey(body,"name",fn);int argsPos=indexOfKey(body,"arguments",fn);
        String name=namePos<0?"":readStringAt(body,namePos);if(argsPos<0)return null;
        int colon=argsPos;while(colon<body.length()&&Character.isWhitespace(body.charAt(colon)))colon++;String argsJson;
        if(colon<body.length()&&body.charAt(colon)=='{'){int end=braceEnd(body,colon);argsJson=body.substring(colon,end+1);} else if(colon<body.length()&&body.charAt(colon)=='\"'){int[] rr=readJsonString(body,colon);if(rr==null)return null;argsJson=unescape(body.substring(colon+1,rr[1]));} else return null;
        String from=extract(argsJson,"from"),to=extract(argsJson,"to"),say=extract(argsJson,"say");if(say==null)say="";
        Move m=new Move();m.action=name==null||name.isEmpty()?"move_piece":name;m.from=from==null?"":from.toLowerCase();m.to=to==null?"":to.toLowerCase();m.say=say;
        if("return_captured_piece".equals(m.action)){m.capturedSide=extract(argsJson,"capturedSide");String pk=extract(argsJson,"piece");m.pieceKind=(pk==null||pk.isEmpty())?0:pk.charAt(0);m.occurrence=extractInt(argsJson,"occurrence",1);}
        if(m.action.equals("move_piece")||m.action.equals("move_friend_piece")){
            if(m.from.isEmpty()||m.to.isEmpty())return null;
            if(!m.from.matches("(?i)[a-i][0-9]")||!m.to.matches("(?i)[a-i][0-9]"))return null;
            m.fc=m.from.charAt(0)-'a';m.fr=m.from.charAt(1)-'0';m.tc=m.to.charAt(0)-'a';m.tr=m.to.charAt(1)-'0';
        } else if(m.action.equals("return_captured_piece")){
            if(m.to==null||!m.to.matches("(?i)[a-i][0-9]"))return null;
            m.tc=m.to.charAt(0)-'a';m.tr=m.to.charAt(1)-'0';
        }
        return m;
    }
    private static String extract(String json,String key){int i=indexOfKey(json,key);return i<0?null:readStringAt(json,i);}
    private static int extractInt(String json,String key,int def){
        int i=indexOfKey(json,key);if(i<0)return def;int p=i;while(p<json.length()&&Character.isWhitespace(json.charAt(p)))p++;int q=p;while(q<json.length()&&json.charAt(q)>='0'&&json.charAt(q)<='9')q++;try{return q>p?Integer.parseInt(json.substring(p,q)):def;}catch(Exception e){return def;}
    }
    private static String readStringAt(String s,int keyEnd){int p=keyEnd;while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;if(p>=s.length()||s.charAt(p)!='\"')return null;int[] r=readJsonString(s,p);return r==null?null:unescape(s.substring(p+1,r[1]));}
    private static int indexOfKey(String s,String key){return indexOfKey(s,key,0);}
    private static int indexOfKey(String s,String key,int start){String pat="\""+key+"\"";int i=s.indexOf(pat,start);if(i<0)return -1;int p=i+pat.length();while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;if(p<s.length()&&s.charAt(p)==':')p++;return p;}
    private static int[] readJsonString(String s,int quotePos){boolean esc=false;for(int i=quotePos+1;i<s.length();i++){char c=s.charAt(i);if(esc){esc=false;continue;}if(c=='\\'){esc=true;continue;}if(c=='\"')return new int[]{0,i};}return null;}
    private static int braceEnd(String s,int open){int depth=0;boolean esc=false,inStr=false;for(int i=open;i<s.length();i++){char c=s.charAt(i);if(inStr){if(esc)esc=false;else if(c=='\\')esc=true;else if(c=='\"')inStr=false;continue;}if(c=='\"')inStr=true;else if(c=='{')depth++;else if(c=='}'){depth--;if(depth==0)return i;}}return s.length()-1;}
    private static String unescape(String raw){StringBuilder sb=new StringBuilder();for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c=='\\'&&i+1<raw.length()){char n=raw.charAt(++i);switch(n){case 'n':sb.append('\n');break;case 't':sb.append('\t');break;case 'r':break;case 'u':if(i+4<raw.length())try{sb.append((char)Integer.parseInt(raw.substring(i+1,i+5),16));i+=4;}catch(NumberFormatException e){sb.append(n);}else sb.append(n);break;default:sb.append(n);}}else sb.append(c);}return sb.toString();}
    private static String esc(String s){StringBuilder sb=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '\"':sb.append("\\\"");break;case '\\':sb.append("\\\\");break;case '\n':sb.append("\\n");break;case '\r':break;case '\t':sb.append("\\t");break;default:if(c<0x20)sb.append(String.format("\\u%04x",(int)c));else sb.append(c);}}return sb.toString();}
}
