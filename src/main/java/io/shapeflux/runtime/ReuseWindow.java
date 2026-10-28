package io.shapeflux.runtime;

import java.util.*;

/** Observed request frequencies only. Forecasts are estimates, never knowledge of future arrivals. */
public final class ReuseWindow {
    public record Estimate(int observed,int span,long gapRequests,double meanGap,double frequency,double predictedUses) {}
    private final int window;
    private final Map<String,ArrayDeque<Long>> occurrences=new HashMap<>();
    private final Map<String,Long> last=new HashMap<>();
    private final Map<String,Double> gaps=new HashMap<>();
    private long request;
    public ReuseWindow(int window) {if(window<1 || window>10000)throw new IllegalArgumentException("Invalid reuse window");this.window=window;}
    public Estimate observe(String key,int forecastRequests) {
        Objects.requireNonNull(key);if(forecastRequests<1)throw new IllegalArgumentException("Invalid forecast horizon");request++;
        long threshold=request-window;
        for(var it=occurrences.entrySet().iterator();it.hasNext();) {
            var e=it.next();while(!e.getValue().isEmpty() && e.getValue().peekFirst()<=threshold)e.getValue().removeFirst();
            if(e.getValue().isEmpty()){it.remove();last.remove(e.getKey());gaps.remove(e.getKey());}
        }
        Long previous=last.put(key,request);long gap=previous==null?0:request-previous;
        if(gap>0)gaps.merge(key,(double)gap,(old,value)->.75*old+.25*value);
        var seen=occurrences.computeIfAbsent(key,k->new ArrayDeque<>());seen.addLast(request);
        int span=(int)Math.min(request,window);double frequency=(double)seen.size()/span;
        return new Estimate(seen.size(),span,gap,gaps.getOrDefault(key,0.0),frequency,frequency*forecastRequests);
    }
    public int retainedRequests() {return occurrences.values().stream().mapToInt(ArrayDeque::size).sum();}
}
