package io.shapeflux.io;

import java.lang.reflect.RecordComponent;
import java.util.*;

/** Small output-only JSON encoder for reproducible metadata; no external dependency. */
public final class Json {
    private Json() {}
    public static String write(Object value) {
        if(value==null)return "null";
        if(value instanceof String s)return quote(s);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number n) {
            if(n instanceof Double d && !Double.isFinite(d) || n instanceof Float f && !Float.isFinite(f))return quote(n.toString());
            return n.toString();
        }
        if(value instanceof Enum<?> e)return quote(e.name());
        if(value instanceof Map<?,?> map) {
            List<String> entries=new ArrayList<>();map.forEach((k,v)->entries.add(quote(k.toString())+":"+write(v)));
            return "{"+String.join(",",entries)+"}";
        }
        if(value instanceof Iterable<?> list) {List<String> items=new ArrayList<>();for(Object v:list)items.add(write(v));return "["+String.join(",",items)+"]";}
        if(value.getClass().isRecord()) {
            Map<String,Object> fields=new LinkedHashMap<>();
            try {for(RecordComponent c:value.getClass().getRecordComponents())fields.put(c.getName(),c.getAccessor().invoke(value));}
            catch(ReflectiveOperationException e){throw new IllegalArgumentException("Cannot encode record",e);}
            return write(fields);
        }
        throw new IllegalArgumentException("Unsupported JSON type: "+value.getClass().getName());
    }
    public static String quote(String value) {
        StringBuilder b=new StringBuilder("\"");
        for(char c:value.toCharArray())switch(c) {
            case '\\'->b.append("\\\\");case '"'->b.append("\\\"");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");
            default->{if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}
        }
        return b.append('"').toString();
    }
}
